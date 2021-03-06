(ns clojure.core.typed.collect-phase
  (:require [clojure.core.typed :refer [*already-collected*]]
            (clojure.core.typed
             [type-rep :as r]
             [type-ctors :as c]
             [utils :as u :refer [constant-exprs p profile]]
             [parse-unparse :as prs]
             [var-env :as var-env]
             [name-env :as nme-env]
             [declared-kind-env :as decl]
             [ns-deps :as dep]
             [ns-options :as ns-opts]
             [utils :as u]
             [util-vars :as uvar]
             [check :as chk]
             [analyze-clj :as ana-clj]
             [current-impl :as impl]
             [free-ops :as free-ops]
             [datatype-ancestor-env :as ancest]
             [rclass-env :as rcls]
             [datatype-env :as dt-env]
             [protocol-env :as ptl-env]
             [var-env :as var-env]
             [method-override-env :as override]
             [method-return-nilables :as ret-nil]
             [method-param-nilables :as param-nil]
             [subtype :as sub])
            [clojure.repl :as repl]
            (clojure.tools.namespace
             [track :as track]
             [dir :as dir]
             [dependency :as ndep])))

(def ns-deps-tracker (atom (track/tracker)))

(defn update-ns-deps! []
  (p :collect/update-ns-deps!
  (try
    (swap! ns-deps-tracker dir/scan)
    (catch StackOverflowError e
      (prn "ERROR COLLECTING DEPENDENCIES: caught StackOverflowError from tools.namespace")))
  ))

(defn parse-field [[n _ t]]
  [n (prs/parse-type t)])

(declare collect)

(defn- collected-ns! [nsym]
  (swap! *already-collected* conj nsym))

(defn- already-collected? [nsym]
  (boolean (@*already-collected* nsym)))

(defn immediate-deps [nsym]
  (ndep/immediate-dependencies (-> @ns-deps-tracker ::track/deps) nsym))

(defn directly-depends? [nsym another-nsym]
  (contains? (immediate-deps nsym) another-nsym))

(defn probably-typed? [nsym]
  (directly-depends? nsym 'clojure.core.typed))

(defn infer-typed-ns-deps!
  "Automatically find other namespaces that are likely to
  be typed dependencies to the current ns."
  [nsym]
  (update-ns-deps!)
  (let [all-deps (immediate-deps nsym)
        new-deps (set (filter probably-typed? all-deps))]
    (dep/add-ns-deps nsym new-deps)))

(defn collect-ns
  "Collect type annotations and dependency information
  for namespace symbol nsym, and recursively check 
  declared typed namespace dependencies."
  ([nsym]
   (p :collect-phase/collect-ns
   (if (already-collected? nsym)
     (do (println (str "Already collected " nsym ", skipping"))
         (flush))
     (do (collected-ns! nsym)
         (infer-typed-ns-deps! nsym)
         (let [deps (dep/immediate-deps nsym)]
           ;(prn "collecting immediate deps for " nsym deps)
           (doseq [dep deps]
             ;(prn "collect:" dep)
             (collect-ns dep)))
         (let [asts (ana-clj/ast-for-ns nsym)]
           (p :collect/collect-form
           (doseq [ast asts]
             (collect ast)))))))))

(defn collect-ns-setup [nsym]
  (binding [*already-collected* (atom #{})]
    (collect-ns nsym)))

(defmulti collect (fn [expr] (:op expr)))
(defmulti invoke-special-collect (fn [expr]
                                   (when-let [var (-> expr :fexpr :var)]
                                     (u/var->symbol var))))

(defmethod collect :do
  [{:keys [exprs] :as expr}]
  (doseq [expr exprs]
    (collect expr)))

(defmethod collect :invoke
  [expr]
  (invoke-special-collect expr))

(defmethod collect :default
  [_]
  nil)

(defn assert-expr-args [{:keys [args] :as expr} cnts]
  {:pre [(set? cnts)]}
  (assert (cnts (count args)))
  (assert (every? #{:constant :keyword :number :string :nil :boolean :empty-expr}
                  (map :op args))
          (mapv :op args)))

(defn gen-datatype* [current-env current-ns provided-name fields vbnd opt record?]
  (let [{ancests :unchecked-ancestors} opt
        ;variances
        vs (seq (map second vbnd))
        args (seq (map first vbnd))
        ctor r/DataType-maker]
    (do (impl/ensure-clojure)
        (let [provided-name-str (str provided-name)
              ;_ (prn "provided-name-str" provided-name-str)
              munged-ns-str (if (some #(= \. %) provided-name-str)
                              (apply str (butlast (apply concat (butlast (partition-by #(= \. %) provided-name-str)))))
                              (str (munge (-> current-ns ns-name))))
              ;_ (prn "munged-ns-str" munged-ns-str)
              demunged-ns-str (str (repl/demunge munged-ns-str))
              ;_ (prn "demunged-ns-str" demunged-ns-str)
              local-name (if (some #(= \. %) provided-name-str)
                           (symbol (apply str (last (partition-by #(= \. %) (str provided-name-str)))))
                           provided-name-str)
              ;_ (prn "local-name" local-name)
              s (symbol (str munged-ns-str \. local-name))
              fs (apply array-map (apply concat (free-ops/with-frees (mapv r/make-F args)
                                                  (binding [uvar/*current-env* current-env
                                                            prs/*parse-type-in-ns* current-ns]
                                                    (mapv parse-field (partition 3 fields))))))
              as (set (free-ops/with-frees (mapv r/make-F args)
                        (binding [uvar/*current-env* current-env
                                  prs/*parse-type-in-ns* current-ns]
                          (mapv prs/parse-type ancests))))
              _ (ancest/add-datatype-ancestors s as)
              pos-ctor-name (symbol demunged-ns-str (str "->" local-name))
              map-ctor-name (symbol demunged-ns-str (str "map->" local-name))
              dt (if (seq args)
                   (c/TypeFn* args vs (repeat (count args) r/no-bounds)
                            (ctor s vs (map r/make-F args) fs record?))
                   (ctor s nil nil fs record?))
              pos-ctor (if args
                          (c/Poly* args (repeat (count args) r/no-bounds)
                                   (r/make-FnIntersection
                                     (r/make-Function (vec (vals fs)) (ctor s vs (map r/make-F args) fs record?)))
                                   args)
                          (r/make-FnIntersection
                            (r/make-Function (vec (vals fs)) dt)))
              map-ctor (when record?
                          (let [hmap-arg (c/-hmap (zipmap (map (comp r/-val keyword) (keys fs))
                                                          (vals fs)))]
                            (if args
                              (c/Poly* args (repeat (count args) r/no-bounds)
                                     (r/make-FnIntersection
                                       (r/make-Function [hmap-arg] (ctor s vs (map r/make-F args) fs record?)))
                                     args)
                              (r/make-FnIntersection
                                (r/make-Function [hmap-arg] dt)))))]
          (do 
            (when vs
              (let [f (mapv r/make-F (repeatedly (count vs) gensym))]
                (rcls/alter-class* s (c/RClass* (map :name f) vs f s {}))))
            (dt-env/add-datatype s dt)
            (var-env/add-var-type pos-ctor-name pos-ctor)
            (var-env/add-nocheck-var pos-ctor-name)
            (when record?
              (override/add-method-override (symbol (str s) "create") map-ctor)
              (var-env/add-var-type map-ctor-name map-ctor)))))))

(defmethod invoke-special-collect 'clojure.core.typed/ann-precord*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{4})
  (let [[dname vbnd fields opt] (constant-exprs args)]
    (gen-datatype* env (chk/expr-ns expr) dname fields vbnd opt true)))

(defmethod invoke-special-collect 'clojure.core.typed/ann-pdatatype*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{4})
  (let [[dname vbnd fields opt] (constant-exprs args)]
    (gen-datatype* env (chk/expr-ns expr) dname fields vbnd opt false)))

(defmethod invoke-special-collect 'clojure.core.typed/ann-datatype*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{3})
  (let [[dname fields opt] (constant-exprs args)]
    (gen-datatype* env (chk/expr-ns expr) dname fields nil opt false)))

(defmethod invoke-special-collect 'clojure.core.typed/ann-record*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{3})
  (let [[dname fields opt] (constant-exprs args)]
    (gen-datatype* env (chk/expr-ns expr) dname fields nil opt true)))

(defmethod invoke-special-collect 'clojure.core.typed/warn-on-unannotated-vars*
  [{:as expr}]
  (assert-expr-args expr #{0})
  (let [prs-ns (chk/expr-ns expr)]
    (ns-opts/register-warn-on-unannotated-vars (ns-name prs-ns))
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/typed-deps*
  [{:keys [args] :as expr}]
  (assert-expr-args expr #{1})
  (let [prs-ns (chk/expr-ns expr)
        [deps] (constant-exprs args)
        _ (assert (and deps (seq deps) (every? symbol? deps)))]
    (dep/add-ns-deps (ns-name prs-ns) (set deps))
    (doseq [dep deps]
      (collect-ns dep))
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/declare-datatypes*
  [{:keys [args] :as expr}]
  (assert-expr-args expr #{1})
  (let [[syms] (constant-exprs args)]
    (doseq [sym syms]
      (assert (not (or (some #(= \. %) (str sym))
                       (namespace sym)))
              (str "Cannot declare qualified datatype: " sym))
      (let [qsym (symbol (str (munge (name (ns-name *ns*))) \. (name sym)))]
        (nme-env/declare-datatype* qsym)))))

;(defmethod invoke-special-collect 'clojure.core.typed/declare-protocols*
;  [{:keys [args] :as expr}]
;  (assert-expr-args expr #{1})
;  (let [[syms] (constant-exprs args)]
;    (doseq [sym syms]
;      (let [qsym (if (namespace sym)
;                   sym
;                   (symbol (str (name (ns-name *ns*))) (name sym)))]
;        (nme-env/declare-protocol* qsym)))))
;
;(defmethod invoke-special-collect 'clojure.core.typed/declare-protocols*
;  [{:keys [args env] :as expr}]
;  (assert-expr-args expr #{2})
;  (let [prs-ns (chk/expr-ns expr)
;        [sym tsyn] (constant-exprs args)
;        _ (assert ((every-pred symbol? (complement namespace)) sym))
;        ty (binding [uvar/*current-env* env
;                     prs/*parse-type-in-ns* prs-ns]
;             (prs/parse-type tsyn))
;        qsym (symbol (-> prs-ns ns-name str) (str sym))]
;    (nme-env/declare-name* qsym)
;    (decl/declare-alias-kind* qsym ty)
;    nil))

(defmethod invoke-special-collect 'clojure.core.typed/declare-names*
  [{:keys [args] :as expr}]
  (assert-expr-args expr #{1})
  (let [nsym (ns-name (chk/expr-ns expr))
        [syms] (constant-exprs args)
        _ (assert (every? (every-pred symbol? (complement namespace)) syms)
                  "declare-names only accepts unqualified symbols")]
    (doseq [sym syms]
      (nme-env/declare-name* (symbol (str nsym) (str sym))))))

(defmethod invoke-special-collect 'clojure.core.typed/ann*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{3})
  (let [prs-ns (chk/expr-ns expr)
        [qsym typesyn check?] (constant-exprs args)
        ;macroexpansion provides qualified symbols
        _ (assert ((every-pred symbol? namespace) qsym))
        expected-type (binding [uvar/*current-env* env
                                prs/*parse-type-in-ns* prs-ns]
                        (prs/parse-type typesyn))]
    (when-not check?
      (var-env/add-nocheck-var qsym))
    (var-env/add-var-type qsym expected-type)
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/def-alias*
  [{:keys [args env env] :as expr}]
  (assert-expr-args expr #{2})
  (let [prs-ns (chk/expr-ns expr)
        [qsym typesyn] (constant-exprs args)
        ;macroexpansion provides qualified symbols
        _ (assert ((every-pred symbol? namespace) qsym))
        alias-type (binding [uvar/*current-env* env
                             prs/*parse-type-in-ns* prs-ns]
                     (prs/parse-type typesyn))]
    ;var already interned via macroexpansion
    (nme-env/add-type-name qsym alias-type)
    (when-let [tfn (@decl/DECLARED-KIND-ENV qsym)]
      (assert (sub/subtype? alias-type tfn) (u/error-msg "Declared kind " (prs/unparse-type tfn)
                                                         " does not match actual kind " (prs/unparse-type alias-type))))
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/non-nil-return*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{2})
  (let [[msym arities] (constant-exprs args)]
    (ret-nil/add-nonnilable-method-return msym arities)
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/nilable-param*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{2})
  (let [[msym mmap] (constant-exprs args)]
    (param-nil/add-method-nilable-param msym mmap)
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/override-method*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{2})
  (let [prs-ns (chk/expr-ns expr)
        [msym tsyn] (constant-exprs args)
        _ (assert (namespace msym) "Method symbol must be a qualified symbol")
        ty (binding [uvar/*current-env* env
                     prs/*parse-type-in-ns* prs-ns]
             (prs/parse-type tsyn))]
    (override/add-method-override msym ty)
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/override-constructor*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{2})
  (let [prs-ns (chk/expr-ns expr)
        [msym tsyn] (constant-exprs args)
        ty (binding [uvar/*current-env* env
                     prs/*parse-type-in-ns* prs-ns]
             (prs/parse-type tsyn))]
    (override/add-method-override msym ty)
    nil))

(defn gen-protocol* [current-env current-ns vsym vbnds mths]
  (let [variances (seq (map second vbnds))
        args (seq (map first vbnds))
        s (if (namespace vsym)
            (symbol vsym)
            (symbol (-> current-ns ns-name str) (name vsym)))
        protocol-defined-in-nstr (namespace s)
        on-class (symbol (str (munge (namespace s)) \. (name s)))
        ; add a Name so the methods can be parsed
        _ (nme-env/declare-protocol* s)
        fs (when args
              (map r/make-F args))
        ms (into {} (for [[knq v] mths]
                       (do
                         (assert (not (namespace knq))
                                  "Protocol method should be unqualified")
                          [knq (free-ops/with-frees fs 
                                 (binding [uvar/*current-env* current-env
                                           prs/*parse-type-in-ns* current-ns]
                                   (prs/parse-type v)))])))
        t (if fs
            (c/TypeFn* (map :name fs) variances (repeat (count fs) r/no-bounds) 
                     (r/Protocol-maker s variances fs on-class ms))
            (r/Protocol-maker s nil nil on-class ms))]
    (ptl-env/add-protocol s t)
    (doseq [[kuq mt] ms]
      (assert (not (namespace kuq))
              "Protocol method names should be unqualified")
      ;qualify method names when adding methods as vars
      (let [kq (symbol protocol-defined-in-nstr (name kuq))]
        (var-env/add-nocheck-var kq)
        (var-env/add-var-type kq mt)))
    nil))

(defmethod invoke-special-collect 'clojure.core.typed/ann-protocol*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{2})
  (let [[varsym mth] (constant-exprs args)]
    (gen-protocol* env (chk/expr-ns expr) varsym nil mth)))

(defmethod invoke-special-collect 'clojure.core.typed/ann-pprotocol*
  [{:keys [args env] :as expr}]
  (assert-expr-args expr #{3})
  (let [[varsym vbnd mth] (constant-exprs args)]
    (gen-protocol* env (chk/expr-ns expr) varsym vbnd mth)))


(defmethod invoke-special-collect :default
  [_]
  nil)
