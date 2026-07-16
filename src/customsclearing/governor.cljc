(ns customsclearing.governor
  "Customs Clearing Governor -- the independent compliance layer that
  earns the customs-clearing advisor the right to commit. The advisor
  has no notion of which tariff schedule is currently in force, whether
  a client (principal) is actually registered with this practice,
  whether a consignment's paperwork is complete, or whether a proposal
  is safe to hand to a human licensed broker for sign-off -- so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  `:itonami.blueprint/governor` is `:customs-clearing-governor`. This
  blueprint represents an INDEPENDENT, self-employed customs clearing
  and freight forwarding practitioner (ISCO-08 3331) -- it is NOT a
  customs authority (no power to grant clearance or admit goods into a
  territory) and it is NOT the practitioner's actual licensed-broker
  signing authority. This actor DRAFTS and COORDINATES only: customs
  declaration drafts, consignment status logging, document-checklist
  verification, compliance-concern flagging, and carrier/broker
  coordination. A human licensed customs broker/forwarder remains
  accountable for every binding act. Actually clearing goods through
  customs, holding itself out as the broker of record, filing a binding
  declaration without human sign-off, or waiving sanctions/restricted-
  party screening are permanently excluded from this actor's proposal
  ops; any attempt to propose them is a HARD violation that no human
  approver can override at this layer (they would need to act entirely
  outside this actor).

  Checks, in priority order:

  HARD (never overridable, always :hold):
    1. principal provenance  -- the request's client/principal must be
                               registered with this practice.
    2. no-actuation           -- proposal :effect must be :propose (this
                               actor never itself clears, files, or
                               executes).
    3. forbidden-operation    -- clearing goods through customs, acting
                               as broker of record, filing a binding
                               declaration, or waiving sanctions/
                               restricted-party screening are
                               permanently out of scope.
    4. spec-basis              -- a customs-declaration draft with no
                               cited tariff/HS-code basis is treated as
                               an invented legal basis, not a real one.

  ESCALATION (always human sign-off, never auto-proceed):
    5. compliance-concern      -- :flag-compliance-concern ALWAYS
                               escalates (e.g. restricted/prohibited
                               goods, a sanctions/restricted-party
                               match, a valuation discrepancy) -- the
                               cardinal escalation trigger for this
                               actor, mirroring the safety-concern
                               escalation used across this fleet.
    6. missing-document        -- a document-checklist verification
                               that finds a required document missing.
    7. confidence floor        -- confidence < `confidence-floor`."
  (:require [customsclearing.store :as store]))

(def confidence-floor 0.6)

(def ^:private forbidden-ops
  "Permanently forbidden operation categories -- this actor never
  actually clears goods through customs, never holds itself out as the
  licensed broker of record, never files a binding declaration without
  human sign-off, and never waives sanctions/restricted-party
  screening. :unknown also lands here: it catches any proposal the
  advisor could not map to a disclosed operation."
  #{:customs/clear-goods
    :customs/admit-goods
    :customs/act-as-broker-of-record
    :customs/file-binding-declaration
    :customs/waive-sanctions-screening
    :unknown})

(def ^:private escalating-ops
  "Operations that always require human approval, even when every other
  check is clean."
  #{:flag-compliance-concern})

(defn- hard-violations [{:keys [proposal]} principal-record]
  (cond-> []
    (nil? principal-record)
    (conj {:rule :no-principal
           :detail "client/principal not registered with this practice"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation
           :detail "effect must be :propose only (no direct clearance/filing writes)"})

    (contains? forbidden-ops (:op proposal))
    (conj {:rule :forbidden-operation
           :detail "operation outside permitted scope (clearing goods through customs, acting as broker of record, filing a binding declaration, and waiving sanctions/restricted-party screening are permanently forbidden)"})

    (and (= :draft-customs-declaration (:op proposal))
         (nil? (:spec-basis proposal)))
    (conj {:rule :no-spec-basis
           :detail "a customs declaration draft with no cited tariff/HS-code basis is an invented legal basis, not a real one"})))

(defn- missing-document-escalation? [proposal]
  (boolean (and (= :verify-document-checklist (:op proposal))
                (seq (:missing proposal)))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `customsclearing.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [principal-record (store/principal store (:principal-id request))
        hard (hard-violations {:proposal proposal} principal-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        escalating-op? (contains? escalating-ops (:op proposal))
        missing-doc? (missing-document-escalation? proposal)]
    {:ok? (and (not hard?) (not low?) (not escalating-op?) (not missing-doc?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? escalating-op? missing-doc?))}))
