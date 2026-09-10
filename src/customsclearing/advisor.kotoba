(ns customsclearing.advisor
  "Customs Clearing Advisor -- the proposal layer for the ISCO-08 3331
  Independent Customs Clearing & Freight Forwarding practice actor.

  Proposes customs-clearing and freight-forwarding coordination
  operations from requests -- drafting a customs declaration, logging a
  consignment, checking a document checklist, flagging a compliance
  concern, coordinating with a carrier/broker -- but never files a
  declaration, never clears goods through customs, never holds itself
  out as the broker of record, and never commits records itself. It
  only ever returns `:propose` proposals for `customsclearing.governor`
  to censor and `customsclearing.actor` to commit once cleared.")

(defprotocol Advisor
  "Advisor protocol for proposing customs-clearing/freight-forwarding
  coordination operations."
  (propose [advisor request context]
    "Propose a coordination operation from a request. Returns a proposal
     map with :op, :effect (always :propose), :confidence, and
     supporting data."))

(defn mock-advisor
  "Default deterministic advisor that proposes standard customs-clearing
  and freight-forwarding coordination operations based on request type.
  Always returns :propose effect. Confidence reflects how routine the
  operation is; a compliance-concern flag deliberately never gets more
  than moderate confidence -- it exists to force escalation, not to
  auto-clear."
  []
  (reify Advisor
    (propose [this request context]
      (let [req-type (:type request)
            op (case req-type
                 :draft-declaration
                 {:op :draft-customs-declaration
                  :confidence 0.8
                  :consignment-id (:consignment-id request)
                  :direction (:direction request)
                  :hs-code (:hs-code request)
                  :declared-value (:declared-value request)
                  :spec-basis (:spec-basis request)}

                 :log-shipment
                 {:op :log-consignment
                  :confidence 0.9
                  :consignment-id (:consignment-id request)
                  :status (:status request)}

                 :verify-documents
                 {:op :verify-document-checklist
                  :confidence 0.85
                  :consignment-id (:consignment-id request)
                  :documents (:documents request)
                  :missing (:missing request)}

                 :flag-concern
                 {:op :flag-compliance-concern
                  :confidence 0.7
                  :consignment-id (:consignment-id request)
                  :concern-type (:concern-type request)
                  :detail (:detail request)}

                 :coordinate-carrier
                 {:op :coordinate-forwarding
                  :confidence 0.8
                  :consignment-id (:consignment-id request)
                  :carrier (:carrier request)
                  :instructions (:instructions request)}

                 {:op :unknown :confidence 0.0})]
        (assoc op :effect :propose)))))

(defn llm-advisor
  "Advisor backed by an LLM (ChatModel). Always returns :propose effect;
  LLM parse failures yield confidence 0.0 (forces escalation)."
  [chat-model]
  (reify Advisor
    (propose [this request context]
      ; Placeholder: real implementation would call chat-model
      ; and parse response to extract :op, :confidence, etc.
      ; On any error, return confidence 0.0
      {:op :unknown :effect :propose :confidence 0.0})))
