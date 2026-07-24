(ns commitledger.advisor
  "Commitment-LLM client -- the *contained intelligence node* for the
  commitment-ledger actor.

  It normalizes application intake, proposes a matched lender + suggested
  terms for `:commitment/record` (citing the application's own
  jurisdiction spec-basis when one exists), and drafts the tranche-
  release authorization for `:commitment/tranche-release`. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record. Every
  output is censored downstream by `commitledger.governor` before
  anything touches the SSoT, and NEITHER actuation op EVER auto-commits
  at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm/
  murakumo-main or equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map            ; op-specific echo/annotation
     :stake      kw|nil         ; :actuation/commitment-record | :actuation/tranche-release | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [commitledger.facts :as facts]
            [commitledger.registry :as registry]
            [commitledger.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the borrower ref, principal, income/debt, lender or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "コミットメント申込レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :application/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- looks-clean?
  "A rough, ADVISOR-SIDE plausibility signal only -- never the source of
  truth (the governor independently re-derives every HARD check). Used
  only to modulate `:confidence`."
  [a]
  (try
    (let [ratio (registry/compute-capacity-ratio a)
          lender (:lender a)
          rate-ok? (if (= :institutional (:lender/type lender))
                      (<= (:proposed-rate a) (registry/rate-ceiling-for-principal (:requested-principal a)))
                      (zero? (:proposed-rate a)))]
      (and (<= ratio registry/capacity-ratio-ceiling)
           rate-ok?
           (registry/personal-pledge-complete? (:personal-pledge a))
           (registry/borrower-ref-well-formed? (:borrower-org-repo a) (:borrower-did a))
           (not (registry/equity-language? (:purpose a)))))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn- propose-commitment
  "Draft the matched-lender + suggested-terms COMMITMENT-RECORD
  proposal -- ALWAYS `:stake :actuation/commitment-record`. Cites the
  application's own jurisdiction spec-basis when one exists in
  `commitledger.facts`; when it does NOT, this honestly returns empty
  `:cites` and a low-confidence note rather than inventing one -- the
  Credit-LLM analog `credit.creditllm/assess-jurisdiction`'s own
  discipline, and the failure mode `commitledger.governor/spec-basis-
  missing-violations` must (and does) reject."
  [db {:keys [subject]}]
  (let [a (store/application db subject)
        sb (facts/spec-basis (:jurisdiction a))
        lender (:lender a)]
    (if (nil? sb)
      {:summary    (str (:jurisdiction a) " の公式spec-basisが見つかりません -- コミットメント記録を提案できません")
       :rationale  "commitledger.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :commitment/mark-recorded
       :value      {:application-id subject :spec-basis nil}
       :stake      :actuation/commitment-record
       :confidence 0.1}
      {:summary    (str subject ": " (get lender :lender/id) " (" (name (get lender :lender/type)) ") とのマッチング + 提案条件 (元本="
                        (:requested-principal a) ", 金利=" (:proposed-rate a) ")")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb)
                        " / personal-pledge=" (pr-str (:personal-pledge a)))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :commitment/mark-recorded
       :value      {:application-id subject :spec-basis (:provenance sb) :legal-basis (:legal-basis sb)
                    :matched-lender-id (get lender :lender/id)}
       :stake      :actuation/commitment-record
       :confidence (if (looks-clean? a) 0.9 0.3)})))

(defn- propose-tranche-release
  "Draft the TRANCHE-RELEASE authorization -- ALWAYS `:stake
  :actuation/tranche-release`. `:tranche-index`/`:milestone-evidence`
  come from the REQUEST itself (the human/upstream system supplies the
  milestone evidence; the advisor does not invent it), the same
  'request carries the ground-truth input, advisor only echoes/cites
  it' shape `vcfund.governor`'s `:call-amount`-style checks establish."
  [db {:keys [subject tranche-index milestone-evidence]}]
  (let [a (store/application db subject)
        committed? (store/application-already-committed? db subject)
        already-released? (and (integer? tranche-index)
                                (store/tranche-already-released? db subject tranche-index))
        evidence-ok? (and (map? milestone-evidence) (true? (:milestone-met? milestone-evidence))
                          (string? (:evidence milestone-evidence)) (not (str/blank? (:evidence milestone-evidence))))]
    {:summary    (str subject " のtranche " tranche-index " リリース提案")
     :rationale  (str "committed?=" committed? " milestone-evidence=" (pr-str milestone-evidence)
                      " already-released?=" already-released?)
     :cites      [subject]
     :effect     :commitment/mark-tranche-released
     :value      {:application-id subject :tranche-index tranche-index}
     :stake      :actuation/tranche-release
     :confidence (if (and committed? evidence-ok? (not already-released?)
                          (integer? tranche-index) (< tranche-index (count (or (:tranche-schedule a) []))))
                   0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :application/intake         (normalize-intake db request)
    :commitment/record            (propose-commitment db request)
    :commitment/tranche-release     (propose-tranche-release db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは個人事業/若年起業家向けコミットメント・レジャー(融資マッチング)エージェントの"
       "助言者です。与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:application/upsert|:commitment/mark-recorded|:commitment/mark-tranche-released) "
       ":value(マップ) :stake(:actuation/commitment-record|:actuation/tranche-release|nil) :confidence(0..1)。\n"
       "重要: このアクターはV1では融資(loan)のみを扱う。出資・持分・配当に関する語句を絶対に"
       "提案してはいけない。登録されていない法域の要件を絶対に創作してはいけない。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :commitment/record            {:application (store/application st subject)}
    :commitment/tranche-release     {:application (store/application st subject)
                                     :committed? (store/application-already-committed? st subject)}
    {:application (store/application st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the CommitmentLedgerGovernor
  escalates/holds -- an LLM hiccup can never auto-record a commitment or
  auto-release a tranche."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :commitllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
