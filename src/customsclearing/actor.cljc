(ns customsclearing.actor
  "Customs Clearing Actor -- the langgraph StateGraph wiring and runtime
  for the ISCO-08 3331 Independent Customs Clearing & Freight Forwarding
  practice. Implements the itonami actor pattern with Advisor/Governor
  separation, a real `interrupt-before` human-in-the-loop checkpoint on
  the escalation path, and an append-only per-run audit trail.

  Node functions here follow `langgraph.graph`'s real Runnable contract:
  a node is `state -> partial-update-map`, folded into the running state
  by `langgraph.graph/apply-updates` (last-write-wins per key, since this
  graph declares no custom channel reducers) -- they do NOT return a full
  replacement state map."
  (:require [langgraph.graph :as graph]
            [langgraph.checkpoint :as checkpoint]
            [customsclearing.store :as store]
            [customsclearing.advisor :as advisor]
            [customsclearing.governor :as governor]))

(defn- intake-node
  "Intake node: accept the incoming request; hand off to :advise."
  [_state]
  {:phase :advise})

(defn- advise-node
  "Advise node: Advisor proposes an operation (never commits, never
  actuates -- see `customsclearing.advisor`)."
  [state advisor-instance]
  (let [request (:request state)
        context (:context state)
        proposal (advisor/propose advisor-instance request context)]
    {:proposal proposal :phase :govern}))

(defn- govern-node
  "Govern node: independent Governor evaluates the proposal against
  ground truth in `store` (see `customsclearing.governor`)."
  [state store-instance]
  (let [request (:request state)
        context (:context state)
        proposal (:proposal state)
        verdict (governor/check request context proposal store-instance)]
    {:decision verdict :phase :decide}))

(defn- route-decision
  "Router for the :decide conditional edge. HARD violations always
  hold (no human override at this layer); anything the governor flags
  for escalation always waits for a human via :request-approval;
  everything else proceeds straight to :commit."
  [state]
  (let [decision (:decision state)]
    (cond
      (:hard? decision)     :hold
      (:escalate? decision) :request-approval
      :else                 :commit)))

(defn- commit-node
  "Commit node: append the cleared proposal to this run's in-state
  audit trail. `store-instance` is accepted (mirroring the actor's
  dependency-injection shape) but this node does not itself mutate it
  -- `store` is an immutable value here, not a mutable cell, so
  persisting committed records into `store` across runs is the
  caller's responsibility once it holds a mutable reference (e.g. an
  atom wrapping `customsclearing.store/MemStore`)."
  [state _store-instance]
  (let [proposal (:proposal state)]
    {:phase :complete
     :records (conj (vec (:records state)) {:recorded true :op (:op proposal)})}))

(defn- request-approval-node
  "Request-approval node: this node is gated behind `interrupt-before`
  in `build-graph`, so it never runs until a human calls `approve!` to
  resume the checkpointed thread. Once it does run, it marks the
  proposal as human-approved and control falls through to :commit."
  [_state]
  {:phase :approved})

(defn- hold-node
  "Hold node: reject the proposal (HARD governance violation).
  Terminal -- no path from :hold ever reaches :commit."
  [state]
  {:phase :rejected
   :error (str "Hard governance violation: " (-> state :decision :violations))})

(defn build-graph
  "Build and compile the StateGraph for the customs-clearing actor.
  `advisor-instance` proposes; `store-instance` grounds the governor's
  checks. `:request-approval` is wired as a genuine `interrupt-before`
  node backed by an in-memory, claim-safe checkpointer
  (`langgraph.checkpoint/mem-checkpointer`): an escalated run really
  halts there (`:status :interrupted`) instead of silently proceeding,
  and only resumes via `approve!`."
  [advisor-instance store-instance]
  (-> (graph/state-graph)
      (graph/add-node :intake intake-node)
      (graph/add-node :advise (fn [s] (advise-node s advisor-instance)))
      (graph/add-node :govern (fn [s] (govern-node s store-instance)))
      (graph/add-node :decide (fn [_s] {:phase :decide}))
      (graph/add-node :commit (fn [s] (commit-node s store-instance)))
      (graph/add-node :request-approval request-approval-node)
      (graph/add-node :hold hold-node)
      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)
      (graph/add-conditional-edges :decide route-decision)
      (graph/add-edge :request-approval :commit)
      (graph/set-finish-point :commit)
      (graph/set-finish-point :hold)
      (graph/compile-graph {:checkpointer (checkpoint/mem-checkpointer)
                             :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run a customs-clearing/freight-forwarding coordination request
  through the compiled actor graph on `thread-id`. Returns the full
  run result `{:state :events :status :frontier}`.

  `:status :interrupted` with `:frontier [:request-approval]` means
  the governor escalated the proposal (low confidence, a compliance-
  concern flag, or a missing-document finding) and this run is
  genuinely paused for human sign-off -- resume it with `approve!`.
  `:status :done` with final `:phase :rejected` means the governor
  HARD-blocked the proposal; nothing was committed and nothing can be
  resumed. `:status :done` with final `:phase :complete` means the
  proposal cleared the governor and was recorded."
  [compiled-graph thread-id initial-request context]
  (graph/run* compiled-graph
              {:request initial-request :context context :phase :intake}
              {:thread-id thread-id}))

(defn approve!
  "Human sign-off for a run `run-request!` returned as `:interrupted`
  at `:request-approval`. Resumes the checkpointed thread with
  `approval-context` folded into state; the request-approval node then
  actually runs (marking the proposal human-approved) and control
  falls through to :commit, so the run genuinely completes rather than
  merely being annotated as approved."
  [compiled-graph thread-id approval-context]
  (graph/run* compiled-graph
              {:approval approval-context}
              {:thread-id thread-id :resume? true}))
