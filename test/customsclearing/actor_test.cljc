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
