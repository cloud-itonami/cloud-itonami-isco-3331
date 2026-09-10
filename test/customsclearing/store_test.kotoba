(ns customsclearing.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [customsclearing.store :as store]))

(deftest test-create-store
  (testing "Create a new store"
    (let [s (store/create-store)]
      (is (not (nil? s)))
      (is (satisfies? store/Store s)))))

(deftest test-register-principal
  (testing "Register and retrieve a client/principal"
    (let [s (store/create-store)
          s' (store/register-principal! s "imp-001" {:name "Acme Importers Ltd" :role :importer})
          retrieved (store/principal s' "imp-001")]
      (is (= (:name retrieved) "Acme Importers Ltd"))
      (is (= (:role retrieved) :importer)))))

(deftest test-register-consignment
  (testing "Register and retrieve a consignment/shipment"
    (let [s (store/create-store)
          s' (store/register-consignment! s "cons-001" {:origin "JP" :destination "US" :mode :ocean})
          retrieved (store/consignment s' "cons-001")]
      (is (= (:origin retrieved) "JP"))
      (is (= (:mode retrieved) :ocean)))))

(deftest test-add-record
  (testing "Add and retrieve records from audit ledger"
    (let [s (store/create-store)
          s' (store/add-record! s :consignment-logged {:consignment-id "cons-001" :status :in-transit})
          recs (store/records s')]
      (is (= (count recs) 1))
      (is (= (:type (first recs)) :consignment-logged)))))

(deftest test-immutability
  (testing "Store operations return new store instances, never mutate in place"
    (let [s (store/create-store)
          s' (store/register-principal! s "imp-001" {:name "Acme Importers Ltd"})
          principal-in-s (store/principal s "imp-001")
          principal-in-s' (store/principal s' "imp-001")]
      (is (nil? principal-in-s))
      (is (not (nil? principal-in-s')))
      (is (= (:name principal-in-s') "Acme Importers Ltd")))))
