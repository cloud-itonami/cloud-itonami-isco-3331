(ns customsclearing.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [customsclearing.advisor :as advisor]))

(deftest test-mock-advisor-draft-declaration
  (testing "Mock advisor proposes a customs-declaration draft, never an actuation"
    (let [a (advisor/mock-advisor)
          request {:type :draft-declaration :consignment-id "cons-001"
                    :direction :import :hs-code "0901.21" :declared-value 12000
                    :spec-basis "WCO Harmonized System 2022, heading 0901"}
          proposal (advisor/propose a request {})]
      (is (= :draft-customs-declaration (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= "0901.21" (:hs-code proposal)))
      (is (= "WCO Harmonized System 2022, heading 0901" (:spec-basis proposal)))
      (is (pos? (:confidence proposal))))))

(deftest test-mock-advisor-log-shipment
  (testing "Mock advisor proposes a consignment log entry"
    (let [a (advisor/mock-advisor)
          request {:type :log-shipment :consignment-id "cons-002" :status :in-transit}
          proposal (advisor/propose a request {})]
      (is (= :log-consignment (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= :in-transit (:status proposal))))))

(deftest test-mock-advisor-verify-documents
  (testing "Mock advisor proposes a document-checklist verification"
    (let [a (advisor/mock-advisor)
          request {:type :verify-documents :consignment-id "cons-003"
                    :documents #{:commercial-invoice} :missing #{:packing-list}}
          proposal (advisor/propose a request {})]
      (is (= :verify-document-checklist (:op proposal)))
      (is (= #{:packing-list} (:missing proposal))))))

(deftest test-mock-advisor-flag-concern
  (testing "Mock advisor proposes a compliance-concern flag but never at ceiling confidence"
    (let [a (advisor/mock-advisor)
          request {:type :flag-concern :consignment-id "cons-004"
                    :concern-type :restricted-goods :detail "possible dual-use item"}
          proposal (advisor/propose a request {})]
      (is (= :flag-compliance-concern (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (< (:confidence proposal) 1.0)))))

(deftest test-mock-advisor-coordinate-carrier
  (testing "Mock advisor proposes carrier/broker coordination"
    (let [a (advisor/mock-advisor)
          request {:type :coordinate-carrier :consignment-id "cons-005"
                    :carrier "Pacific Forwarding Co" :instructions "arrange pickup"}
          proposal (advisor/propose a request {})]
      (is (= :coordinate-forwarding (:op proposal)))
      (is (= "Pacific Forwarding Co" (:carrier proposal))))))

(deftest test-mock-advisor-unknown-request-type
  (testing "An unrecognized request type yields :unknown with zero confidence, forcing governor rejection"
    (let [a (advisor/mock-advisor)
          request {:type :not-a-real-request-type}
          proposal (advisor/propose a request {})]
      (is (= :unknown (:op proposal)))
      (is (= 0.0 (:confidence proposal)))
      (is (= :propose (:effect proposal))))))

(deftest test-mock-advisor-always-propose-effect
  (testing "Every mock-advisor proposal has :effect :propose, never an actuation verb"
    (let [a (advisor/mock-advisor)
          request-types [:draft-declaration :log-shipment :verify-documents
                          :flag-concern :coordinate-carrier :bogus-type]]
      (doseq [t request-types]
        (is (= :propose (:effect (advisor/propose a {:type t} {}))))))))

(deftest test-llm-advisor-parse-failure-yields-zero-confidence
  (testing "The LLM-backed advisor placeholder always returns :propose and forces escalation on failure"
    (let [a (advisor/llm-advisor nil)
          proposal (advisor/propose a {:type :draft-declaration} {})]
      (is (= :propose (:effect proposal)))
      (is (= 0.0 (:confidence proposal))))))
