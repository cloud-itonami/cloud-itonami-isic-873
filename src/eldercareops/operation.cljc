(ns eldercareops.operation
  "OperationActor -- one coordination request = one supervised actor
  run, expressed as a langgraph-clj StateGraph. The advisor
  (ElderCareAdvisor) is sealed into a single node (:advise); its
  proposal is ALWAYS routed through the ElderCareGovernor (:govern)
  and the rollout phase gate (:decide) before anything commits to the
  SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today)              - `store` arg
    - the Advisor  (mock | real LLM)              - :advisor opt
    - the Phase    (0->3 rollout)                 - :phase in ctx

  One graph run = one coordination request (intake -> advise -> govern
  -> decide -> commit | hold | approval). No unbounded inner loop --
  each operation is auditable and checkpointed.

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a
  human operator/care coordinator. The approver resumes with
  `{:approval {:status :approved}}` (or :rejected)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [eldercareops.advisor :as advisor]
            [eldercareops.governor :as governor]
            [eldercareops.phase :as phase]
            [eldercareops.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :resident-id (:resident-id request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:op      (:op proposal)
   :resident-id (:resident-id request)
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `eldercareops.store/Store`).
  opts:
    :advisor      -- an `eldercareops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ElderCareAdvisor inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; ElderCareGovernor -- independent censor (separate system than the advisor).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which
      ;; can only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :resident-id (:resident-id request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :always-escalate
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)
               :audit [(commit-fact request context proposal)]}))))

      ;; Branch: :hold -> END, :escalate -> APPROVAL gate, :commit -> LEDGER-APPEND
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :hold :end
            :escalate :request-approval
            :commit :append-ledger
            :end))
        {:end :end :request-approval :request-approval :append-ledger :append-ledger})

      ;; Request-approval node: pause for human review. Resumes via
      ;; `{:approval {:status :approved}}` or `{:status :rejected}`.
      (g/add-node :request-approval
        (fn [s] {:audit [{:t :awaiting-approval
                          :op (get-in s [:request :op])
                          :resident-id (get-in s [:request :resident-id])}]}))

      ;; Branch from request-approval (resume point).
      (g/add-conditional-edges :request-approval
        (fn [{:keys [approval]}]
          (when approval
            (case (:status approval)
              :approved :append-ledger
              :rejected :end)))
        {:append-ledger :append-ledger :end :end})

      ;; Append-ledger node: persist the approved/auto-committed record.
      (g/add-node :append-ledger
        (fn [{:keys [record audit] :as s}]
          (when record (store/commit-record! store record))
          (doseq [fact audit] (store/append-ledger! store fact))
          {:audit audit}))

      ;; END node (sink).
      (g/add-node :end (fn [s] s))

      ;; Edges
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-edge :append-ledger :end)

      (g/compile checkpointer)))
