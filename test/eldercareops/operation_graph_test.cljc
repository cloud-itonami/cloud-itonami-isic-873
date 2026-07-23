(ns eldercareops.operation-graph-test
  "Integration tests for `eldercareops.operation/build` -- proves the
  REAL compiled `langgraph.graph` StateGraph runs end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes. No prior test file in this repo exercised
  `operation/build` at all -- every other test covers
  governor/phase/advisor/store in isolation, which proves those pure
  functions work but not that the graph wiring actually threads them
  together."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [eldercareops.operation :as operation]
            [eldercareops.store :as store]))

(def ^:private op-context {:actor-id "operator-01" :phase 3})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest commit-path-log-care-note-auto-commits-in-phase-3
  (testing ":log-care-note is in phase-3's :auto set -- a clean
            proposal for a registered/verified resident commits
            straight through the REAL compiled graph with no
            interrupt, and the ledger is verified EMPTY before the run
            so the post-run fact is genuinely this run's own effect"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-commit"
                         {:op :log-care-note :resident-id "resident-1"
                          :patch {:mood :happy}})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-care-note (:op (first ledger)))))))))

(deftest hard-hold-resident-unverified-blocks-before-escalation
  (testing "resident-3 is registered but NOT verified -- a HARD
            governor violation. The real graph routes straight to
            :hold, never pausing for human approval"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-hold"
                         {:op :log-care-note :resident-id "resident-3"
                          :patch {:mood :quiet}})
            state (:state result)]
        (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
        (is (= :hold (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (some #{:resident-unverified} (map :rule (:violations (first ledger))))))))))

(deftest escalate-then-approve-commits-and-genuinely-consults-advisor
  (testing ":flag-safety-concern is NEVER in any phase's :auto set, so
            even a Governor-clean proposal GENUINELY interrupts
            (checkpointed) at :request-approval -- the ledger stays
            EMPTY until a human resumes it. Also proves the Advisor's
            real proposal (a randomly generated, single-use :concern
            string, impossible to have been hardcoded in
            eldercareops.operation) threads through
            :advise -> :govern -> :decide -> :request-approval -> :commit"
    (let [distinctive-concern (str "TEST-CONCERN-" (rand-int 1000000000))
          s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [held (exec actor "t-escalate"
                       {:op :flag-safety-concern :resident-id "resident-1"
                        :patch {:concern distinctive-concern}})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
        (let [approved (g/run* actor {:approval {:status :approved :by "care-coordinator-01"}}
                               {:thread-id "t-escalate" :resume? true})
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [ledger (store/ledger s)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= :flag-safety-concern (:op (first ledger)))))
          (let [[record] (store/coordination-log s)]
            (is (= distinctive-concern (:concern (:payload record)))
                "the committed record carries the INJECTED distinctive
                concern string -- proof the graph genuinely threads the
                Advisor's real proposal through rather than hardcoding
                a pass-string")))))))

(deftest escalate-then-reject-holds
  (testing "a human care coordinator rejecting an escalated
            :flag-safety-concern routes to :hold via the
            :request-approval node's own decision, and durably records
            the rejection -- not a hand-rolled parallel path"
    (let [s (store/seed-db)
          actor (operation/build s)
          _held (exec actor "t-reject"
                      {:op :flag-safety-concern :resident-id "resident-2"
                       :patch {:concern "possible fall witnessed"}})
          rejected (g/run* actor {:approval {:status :rejected :by "care-coordinator-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))))))
