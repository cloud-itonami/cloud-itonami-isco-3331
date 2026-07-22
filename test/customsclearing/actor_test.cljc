(ns customsclearing.actor-test
  "End-to-end tests of the compiled StateGraph -- these exercise the real
  langgraph.graph runtime (not a mock), proving `customsclearing.actor`
  actually wires Advisor -> Governor -> commit/hold/escalate correctly,
  including a genuine interrupt-before / resume round trip for the
  escalation path."
  (:require [clojure.test :refer [deftest is testing]]
            [customsclearing.actor :as actor]
            [customsclearing.advisor :as advisor]
            [customsclearing.store :as store]))

(defn- registered-store []
  (-> (store/create-store)
      (store/register-principal! "imp-001" {:name "Acme Importers Ltd"})))

(deftest test-run-request-commits-clean-proposal
  (testing "A routine, well-formed request runs end to end and commits"
    (let [g (actor/build-graph (advisor/mock-advisor) (registered-store))
          result (actor/run-request! g "thread-commit"
                                      {:principal-id "imp-001" :type :log-shipment
                                       :consignment-id "cons-001" :status :in-transit}
                                      {})]
      (is (= :done (:status result)))
      (is (= :complete (-> result :state :phase)))
      (is (= 1 (count (-> result :state :records))))
      (is (= :log-consignment (-> result :state :records first :op))))))

(deftest test-run-request-holds-forbidden-operation
  (testing "A forbidden operation is HARD-blocked and never resumable"
    (let [g (actor/build-graph
             (reify advisor/Advisor
               (propose [_ _request _context]
                 {:op :customs/clear-goods :effect :propose :confidence 0.99}))
             (registered-store))
          result (actor/run-request! g "thread-hold"
                                      {:principal-id "imp-001" :type :n/a}
                                      {})]
      (is (= :done (:status result)))
      (is (= :rejected (-> result :state :phase)))
      (is (some? (-> result :state :error)))
      (is (nil? (-> result :state :records))))))

(deftest test-run-request-escalates-and-resumes-on-approval
  (testing "A compliance-concern flag genuinely interrupts before commit, and only
            proceeds once a human calls approve! to resume the thread"
    (let [g (actor/build-graph (advisor/mock-advisor) (registered-store))
          escalated (actor/run-request! g "thread-escalate"
                                         {:principal-id "imp-001" :type :flag-concern
                                          :consignment-id "cons-002"
                                          :concern-type :restricted-goods
                                          :detail "possible dual-use item"}
                                         {})]
      (is (= :interrupted (:status escalated)))
      (is (= [:request-approval] (:frontier escalated)))
      ;; Nothing committed yet -- the human hasn't signed off.
      (is (nil? (-> escalated :state :records)))
      (let [resumed (actor/approve! g "thread-escalate" {:approver "human-broker-01"})]
        (is (= :done (:status resumed)))
        (is (= :complete (-> resumed :state :phase)))
        (is (= "human-broker-01" (-> resumed :state :approval :approver)))
        (is (= 1 (count (-> resumed :state :records))))
        (is (= :flag-compliance-concern (-> resumed :state :records first :op)))))))

(deftest test-run-request-escalates-on-low-confidence
  (testing "Low-confidence proposals also interrupt rather than silently proceeding"
    (let [g (actor/build-graph
             (reify advisor/Advisor
               (propose [_ _request _context]
                 {:op :log-consignment :effect :propose :confidence 0.1
                  :consignment-id "cons-003" :status :in-transit}))
             (registered-store))
          result (actor/run-request! g "thread-low-conf"
                                      {:principal-id "imp-001" :type :n/a}
                                      {})]
      (is (= :interrupted (:status result)))
      (is (= [:request-approval] (:frontier result))))))

(deftest test-commit-persists-to-store-independent-of-run-state
  (testing "A committed record is genuinely persisted into the injected store's
            append-only ledger, verifiable independently of the graph's own
            transient `:state :records` -- regression test for the dead-ledger
            bug where `:commit` only ever touched run-state and never called
            `store/add-record!`, so nothing survived past a single run"
    (let [g (actor/build-graph (advisor/mock-advisor) (registered-store))
          result (actor/run-request! g "thread-persist-commit"
                                      {:principal-id "imp-001" :type :log-shipment
                                       :consignment-id "cons-999" :status :in-transit}
                                      {})
          persisted (store/records (actor/store-snapshot g))]
      (is (= :done (:status result)))
      (is (= 1 (count persisted)))
      (is (= :consignment-committed (:type (first persisted))))
      (is (= :log-consignment (:op (first persisted))))
      (is (some? (:timestamp (first persisted)))))))

(deftest test-hold-persists-to-store-independent-of-run-state
  (testing "A HARD-blocked (held) proposal is ALSO genuinely persisted into the
            injected store's ledger, not just left out of run-state entirely"
    (let [g (actor/build-graph
             (reify advisor/Advisor
               (propose [_ _request _context]
                 {:op :customs/clear-goods :effect :propose :confidence 0.99}))
             (registered-store))
          result (actor/run-request! g "thread-persist-hold"
                                      {:principal-id "imp-001" :type :n/a}
                                      {})
          persisted (store/records (actor/store-snapshot g))]
      (is (= :done (:status result)))
      (is (= :rejected (-> result :state :phase)))
      (is (= 1 (count persisted)))
      (is (= :consignment-held (:type (first persisted))))
      (is (= :customs/clear-goods (-> persisted first :proposal :op))))))

(deftest test-escalate-then-approve-persists-to-store
  (testing "Persistence also happens correctly across the interrupt/resume path:
            nothing is persisted while interrupted, and the commit lands once
            a human approves"
    (let [g (actor/build-graph (advisor/mock-advisor) (registered-store))
          escalated (actor/run-request! g "thread-persist-escalate"
                                         {:principal-id "imp-001" :type :flag-concern
                                          :consignment-id "cons-777"
                                          :concern-type :restricted-goods
                                          :detail "possible dual-use item"}
                                         {})]
      (is (= :interrupted (:status escalated)))
      (is (empty? (store/records (actor/store-snapshot g))))
      (let [resumed (actor/approve! g "thread-persist-escalate" {:approver "human-broker-01"})
            persisted (store/records (actor/store-snapshot g))]
        (is (= :done (:status resumed)))
        (is (= 1 (count persisted)))
        (is (= :consignment-committed (:type (first persisted))))
        (is (= :flag-compliance-concern (:op (first persisted))))))))

(deftest test-multiple-runs-on-one-graph-accumulate-in-store
  (testing "Two separate runs on the SAME compiled graph both land in the same
            persisted store (proves the store-ref is genuinely shared across
            runs, not reset per run)"
    (let [g (actor/build-graph (advisor/mock-advisor) (registered-store))]
      (actor/run-request! g "thread-multi-1"
                           {:principal-id "imp-001" :type :log-shipment
                            :consignment-id "cons-a" :status :in-transit}
                           {})
      (actor/run-request! g "thread-multi-2"
                           {:principal-id "imp-001" :type :log-shipment
                            :consignment-id "cons-b" :status :in-transit}
                           {})
      (is (= 2 (count (store/records (actor/store-snapshot g))))))))
