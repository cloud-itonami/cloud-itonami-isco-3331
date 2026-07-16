(ns customsclearing.store
  "Customs Clearing Store -- the append-only audit ledger and persistent
  state for the ISCO-08 3331 Independent Customs Clearing & Freight
  Forwarding practice actor. Implements the Store protocol for
  principal (client) identity verification, consignment/shipment
  records, and coordination-record audit trail.

  A `principal` is the client this independent practitioner is engaged
  by (an importer, exporter, or consignee) -- not a customs authority
  and not this actor's own operator. A `consignment` is a shipment/lot
  moving through customs clearance and/or freight forwarding.")

(defprotocol Store
  "Store protocol for customs-clearing actor state and audit ledger."
  (principal [store principal-id]
    "Retrieve a client/principal record by ID. Returns nil if not found.")
  (consignment [store consignment-id]
    "Retrieve a consignment/shipment record by ID. Returns nil if not found.")
  (register-principal! [store principal-id principal-data]
    "Register a client/principal (adds to store, returns updated store).")
  (register-consignment! [store consignment-id consignment-data]
    "Register a consignment/shipment (adds to store, returns updated store).")
  (add-record! [store record-type record-data]
    "Append an immutable coordination record to the audit ledger.")
  (records [store]
    "Return all records in the audit ledger (immutable)."))

(defrecord MemStore [principals consignments ledger]
  Store
  (principal [this principal-id]
    (get principals principal-id))
  (consignment [this consignment-id]
    (get consignments consignment-id))
  (register-principal! [this principal-id principal-data]
    (MemStore. (assoc principals principal-id principal-data) consignments ledger))
  (register-consignment! [this consignment-id consignment-data]
    (MemStore. principals (assoc consignments consignment-id consignment-data) ledger))
  (add-record! [this record-type record-data]
    (let [record (assoc record-data :type record-type :timestamp (System/currentTimeMillis))]
      (MemStore. principals consignments (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new in-memory store for customs-clearing practice records."
  []
  (MemStore. {} {} []))
