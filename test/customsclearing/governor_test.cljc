(ns customsclearing.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [customsclearing.governor :as gov]
            [customsclearing.store :as store]))

(deftest test-hard-violation-unregistered-principal
  (testing "Unregistered client/principal is a hard violation"
    (let [test-store (store/create-store)
          request {:principal-id "unknown-principal"}
          context {}
          proposal {:op :log-consignment :effect :propose :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (false? (:ok? verdict)))
      (is (some #(= :no-principal (:rule %)) (:violations verdict))))))

(deftest test-hard-violation-wrong-effect
  (testing "Non-:propose effect is a hard violation (this actor never actuates)"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :log-consignment :effect :execute :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (seq (:violations verdict))))))

(deftest test-hard-violation-forbidden-clear-goods
  (testing "Actually clearing goods through customs is permanently forbidden"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :customs/clear-goods :effect :propose :confidence 0.95}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :forbidden-operation (:rule %)) (:violations verdict))))))

(deftest test-hard-violation-forbidden-broker-of-record
  (testing "Holding itself out as the licensed broker of record is permanently forbidden"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :customs/act-as-broker-of-record :effect :propose :confidence 0.95}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :forbidden-operation (:rule %)) (:violations verdict))))))

(deftest test-hard-violation-unknown-op
  (testing "Unknown/out-of-scope operations are hard violations"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :unknown :effect :propose :confidence 0.9}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (seq (:violations verdict))))))

(deftest test-hard-violation-no-spec-basis
  (testing "A customs declaration draft with no cited tariff/HS-code basis is an invented legal basis"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :draft-customs-declaration :effect :propose :confidence 0.9
                     :consignment-id "cons-001" :hs-code "0901.21" :spec-basis nil}
          verdict (gov/check request context proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :no-spec-basis (:rule %)) (:violations verdict))))))

(deftest test-valid-declaration-with-spec-basis-passes
  (testing "A declaration draft that cites a real tariff-schedule basis is not a hard violation"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :draft-customs-declaration :effect :propose :confidence 0.9
                     :consignment-id "cons-001" :hs-code "0901.21"
                     :spec-basis "WCO Harmonized System 2022, heading 0901"}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:ok? verdict))))))

(deftest test-valid-log-consignment-passes
  (testing "A routine, well-formed log-consignment proposal from a registered principal passes governor cleanly"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :log-consignment :effect :propose :confidence 0.9
                     :consignment-id "cons-001" :status :in-transit}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (true? (:ok? verdict))))))

(deftest test-low-confidence-escalation
  (testing "Low confidence (<0.6) triggers escalation, not auto-proceed"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :log-consignment :effect :propose :confidence 0.4
                     :consignment-id "cons-001" :status :in-transit}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-compliance-concern-always-escalates
  (testing "Flagging a compliance concern ALWAYS escalates to a human, even at high confidence"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :flag-compliance-concern :effect :propose :confidence 0.95
                     :consignment-id "cons-001" :concern-type :restricted-goods
                     :detail "declared goods appear on the restricted list"}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-missing-document-escalation
  (testing "A document-checklist verification that finds a required document missing escalates"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :verify-document-checklist :effect :propose :confidence 0.9
                     :consignment-id "cons-001"
                     :documents #{:commercial-invoice :packing-list}
                     :missing #{:certificate-of-origin}}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-complete-document-checklist-passes
  (testing "A document-checklist verification with nothing missing passes cleanly"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :verify-document-checklist :effect :propose :confidence 0.9
                     :consignment-id "cons-001"
                     :documents #{:commercial-invoice :packing-list :certificate-of-origin}
                     :missing #{}}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (true? (:ok? verdict))))))

(deftest test-coordinate-forwarding-passes
  (testing "A routine carrier-coordination proposal passes cleanly"
    (let [test-store (-> (store/create-store)
                          (store/register-principal! "imp-001" {:name "Acme Importers Ltd"}))
          request {:principal-id "imp-001"}
          context {}
          proposal {:op :coordinate-forwarding :effect :propose :confidence 0.8
                     :consignment-id "cons-001" :carrier "Pacific Forwarding Co"
                     :instructions "arrange pickup at bonded warehouse 7/18"}
          verdict (gov/check request context proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (true? (:ok? verdict))))))
