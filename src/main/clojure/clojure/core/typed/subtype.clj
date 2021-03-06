(ns clojure.core.typed.subtype
  (:require (clojure.core.typed
             [current-impl :as impl]
             [type-rep :as r]
             [type-ctors :as c]
             [utils :as u :refer [p]]
             [util-vars :as vs]
             [parse-unparse :as prs]
             [filter-rep :as fr]
             [filter-ops :as fops]
             [object-rep :as orep]
             [frees :as frees]
             [free-ops :as free-ops]
             [datatype-ancestor-env :as dtenv])
            [clojure.set :as set])
  (:import (clojure.core.typed.type_rep Poly TApp Union Intersection Value Function
                                        Result Protocol TypeFn Name F Bounds HeterogeneousVector
                                        PrimitiveArray DataType RClass HeterogeneousMap
                                        HeterogeneousList HeterogeneousSeq CountRange KwArgs
                                        Extends)
           (clojure.core.typed.filter_rep FilterSet)
           (clojure.lang APersistentMap APersistentVector PersistentList ASeq Seqable)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subtype

(defmacro handle-failure [& body]
  `(u/handle-subtype-failure ~@body))

;[Type Type -> Nothing]
(defn fail! [s t]
  (throw u/subtype-exn))

;keeps track of currently seen subtype relations for recursive types.
;(Set [Type Type])
(def ^:dynamic *sub-current-seen* #{})

(defn currently-subtyping? []
  (boolean (seq *sub-current-seen*)))

(declare subtypes*-varargs)

;[(Seqable Type) (Seqable Type) Type -> Boolean]
(defn subtypes-varargs?
  "True if argtys are under dom"
  [argtys dom rst]
  (handle-failure
    (subtypes*-varargs #{} argtys dom rst)
    true))

;subtype and subtype? use *sub-current-seen* for remembering types (for Rec)
;subtypeA* takes an extra argument (the current-seen subtypes), called by subtype
;
; In short, only call subtype (or subtype?)

(declare subtype)

(def subtype-cache (atom {}))

(defn reset-subtype-cache []
  (reset! subtype-cache {}))

;[Type Type -> Boolean]
(defn subtype? [s t]
  {:post [(u/boolean? %)]}
  (letfn [(do-subtype []
            (p :subtype-subtype?
               (boolean
                 (handle-failure
                   (subtype s t)))))]
    (if-let [[_ res] (p :subtype-cache-lookup (find @subtype-cache [(hash s) (hash t)]))]
      (p :subtype-cache-hit 
       res)
      (let [_ (p :subtype-cache-miss)
            res (do-subtype)]
        (when-not (currently-subtyping?)
          (swap! subtype-cache assoc [(hash s) (hash t)] res))
        res))))

(declare subtypeA*)

;[(IPersistentSet '[Type Type]) Type Type -> Boolean]
(defn subtypeA*? [A s t]
  (handle-failure
    (subtypeA* A s t)))

(declare supertype-of-one-arr)

(defn infer-var []
  (let [v (ns-resolve (find-ns 'clojure.core.typed.cs-gen) 'infer)]
    (assert (var? v) "infer unbound")
    v))

;[(IPersistentMap Symbol Bounds) (Seqable Type) (Seqable Type)
;  -> Boolean]
(defn unify [X S T]
  (let [infer @(infer-var)]
    (boolean 
      (u/handle-cs-gen-failure
        (infer X {} S T r/-any)))))

(declare subtype-TApp? protocol-descendants
         subtype-datatype-record-on-left subtype-datatype-record-on-right
         subtype-datatypes-or-records subtype-Result subtype-PrimitiveArray
         subtype-CountRange subtype-TypeFn subtype-RClass)

;TODO replace hardcoding cases for unfolding Mu? etc. with a single case for unresolved types.
;[(IPersistentSet '[Type Type]) Type Type -> (IPersistentSet '[Type Type])]
(defn subtypeA* [A s t]
  {:post [(set? %)]}
  (if (or (contains? A [s t])
          (= s t)
          (r/Top? t)
          (r/Bottom? s)
          ;TCError is top and bottom
          (some r/TCError? [s t]))
    A
    (binding [*sub-current-seen* (conj A [s t])]
      (cond
        (and (r/Value? s)
             (r/Value? t))
        ;already (not= s t)
        (fail! s t)

        (r/Name? s)
        (subtypeA* *sub-current-seen* (c/resolve-Name s) t)

        (r/Name? t)
        (subtypeA* *sub-current-seen* s (c/resolve-Name t))

        (and (r/Poly? s)
             (r/Poly? t)
             (= (.nbound ^Poly s) (.nbound ^Poly t)))
        (let [names (repeatedly (.nbound ^Poly s) gensym)
              b1 (c/Poly-body* names s)
              b2 (c/Poly-body* names t)]
          (subtype b1 b2))

        ;use unification to see if we can use the Poly type here
        (and (r/Poly? s)
             (let [names (repeatedly (.nbound ^Poly s) gensym)
                   bnds (c/Poly-bbnds* names s)
                   b1 (c/Poly-body* names s)]
               (unify (zipmap names bnds) [b1] [t])))
        (let [names (repeatedly (.nbound ^Poly s) gensym)
              bnds (c/Poly-bbnds* names s)
              b1 (c/Poly-body* names s)]
          (if (unify (zipmap names bnds) [b1] [t])
            *sub-current-seen*
            (fail! s t)))

        (and (r/Poly? t)
             (let [names (repeatedly (.nbound ^Poly t) gensym)
                   b (c/Poly-body* names t)]
               (empty? (frees/fv t))))
        (let [names (repeatedly (.nbound ^Poly t) gensym)
              b (c/Poly-body* names t)]
          (subtype s b))

        (and (r/TApp? s)
             (r/TApp? t))
        (if (subtype-TApp? s t)
          *sub-current-seen*
          (fail! s t))

        (r/TApp? s)
        (let [^TApp s s]
          (if (and (not (r/F? (.rator s)))
                   (subtypeA*? (conj *sub-current-seen* [s t])
                               (c/resolve-TApp s) t))
            *sub-current-seen*
            (fail! s t)))

        (r/TApp? t)
        (let [^TApp t t]
          (if (and (not (r/F? (.rator t)))
                   (subtypeA*? (conj *sub-current-seen* [s t])
                               s (c/resolve-TApp t)))
            *sub-current-seen*
            (fail! s t)))

        (r/App? s)
        (subtypeA* *sub-current-seen* (c/resolve-App s) t)

        (r/App? t)
        (subtypeA* *sub-current-seen* s (c/resolve-App t))

        (r/Bottom? t)
        (fail! s t)

        (r/Union? s)
        ;use subtypeA*, throws error
        (p :subtype-union-l
        (if (every? (fn union-left [s] (subtypeA* *sub-current-seen* s t)) (.types ^Union s))
          *sub-current-seen*
          (fail! s t))
           )

        ;use subtypeA*?, boolean result
        (r/Union? t)
        (p :subtype-union-r
        (if (some (fn union-right [t] (subtypeA*? *sub-current-seen* s t)) (.types ^Union t))
          *sub-current-seen*
          (fail! s t))
           )

        (and (r/FnIntersection? s)
             (r/FnIntersection? t))
        (loop [A* *sub-current-seen*
               arr2 (:types t)]
          (let [arr1 (:types s)]
            (if (empty? arr2) 
              A*
              (if-let [A (supertype-of-one-arr A* (first arr2) arr1)]
                (recur A (next arr2))
                (fail! s t)))))

        (and (r/Intersection? s)
             (r/Intersection? t))
        (if (every? (fn intersection-both [s*]
                      (some (fn intersection-both-inner [t*] (subtype? s* t*)) (.types ^Intersection t)))
                    (.types ^Intersection s))
          *sub-current-seen*
          (fail! s t))

        (r/Intersection? s)
        (if (some #(subtype? % t) (.types ^Intersection s))
          *sub-current-seen*
          (fail! s t))

        (r/Intersection? t)
        (if (every? #(subtype? s %) (.types ^Intersection t))
          *sub-current-seen*
          (fail! s t))

        (r/Mu? s)
        (subtype (c/unfold s) t)

        (r/Mu? t)
        (subtype s (c/unfold t))

        (and (r/TopFunction? t)
             (r/FnIntersection? s))
        *sub-current-seen*

        (and (r/HeterogeneousVector? s)
             (r/HeterogeneousVector? t))
        (let [^HeterogeneousVector s s
              ^HeterogeneousVector t t]
          (if (and (= (count (.types s))
                      (count (.types t)))
                   ; ignore interesting results
                   (every? (fn [[f1 f2]] (or (= (fops/-FS fr/-top fr/-top) f2)
                                             (= f1 f2)))
                           (map vector (.fs s) (.fs t)))
                   ; ignore interesting results
                   (every? (fn [[o1 o2]] (or (orep/EmptyObject? o2)
                                             (= o1 o2)))
                           (map vector (.objects s) (.objects t))))
            (or (last (doall (map subtype (.types s) (.types t))))
                #{})
            (fail! s t)))

        (and (r/HeterogeneousList? s)
             (r/HeterogeneousList? t))
        (if (= (count (:types s))
               (count (:types t)))
          (or (last (doall (map subtype (:types s) (:types t))))
              #{})
          (fail! s t))

        (and (r/HeterogeneousSeq? s)
             (r/HeterogeneousSeq? t))
        (if (= (count (:types s))
               (count (:types t)))
          (or (last (doall (map #(subtype %1 %2) (:types s) (:types t))))
              #{})
          (fail! s t))

          ;every rtype entry must be in ltypes
          ;eg. {:a 1, :b 2, :c 3} <: {:a 1, :b 2}
          (and (r/HeterogeneousMap? s)
               (r/HeterogeneousMap? t))
          (p :subtype-HMap
          (let [{ltypes :types :as s} s
                {rtypes :types :as t} t]
            (or (last (doall (map (fn [[k v]]
                                    (if-let [t (ltypes k)]
                                      (subtype t v)
                                      (fail! s t)))
                                  rtypes)))
                #{}))
             )

        (r/HeterogeneousMap? s)
        (let [^HeterogeneousMap s s]
          ; Partial HMaps do not record absence of fields, only subtype to (APersistentMap Any Any)
          (if (c/complete-hmap? s)
            (subtype (c/RClass-of APersistentMap [(apply c/Un (keys (.types s)))
                                                  (apply c/Un (vals (.types s)))]) 
                     t)
            (subtype (c/RClass-of APersistentMap [r/-any r/-any]) t)))

        (r/KwArgsSeq? s)
        (subtype (c/Un r/-nil (c/RClass-of Seqable [r/-any])) t)

        (r/HeterogeneousVector? s)
        (let [ss (apply c/Un (:types s))]
          (subtype (c/In (c/RClass-of APersistentVector [ss])
                         (r/make-ExactCountRange (count (:types s))))
                   t))

        (r/HeterogeneousList? s)
        (let [ss (apply c/Un (:types s))]
          (subtype (c/RClass-of PersistentList [ss]) t))

        (r/HeterogeneousSeq? s)
        (let [ss (apply c/Un (:types s))]
          (subtype (c/RClass-of (u/Class->symbol ASeq) [ss])
                   t))

; check protocols before datatypes
        (and (r/Protocol? s)
             (r/Protocol? t))
        (let [{var1 :the-var variances* :variances poly1 :poly?} s
              {var2 :the-var poly2 :poly?} t]
          (if (and (= var1 var2)
                   (every? (fn [[v l r]]
                             (case v
                               :covariant (subtypeA* *sub-current-seen* l r)
                               :contravariant (subtypeA* *sub-current-seen* r l)
                               :invariant (and (subtypeA* *sub-current-seen* l r)
                                               (subtypeA* *sub-current-seen* r l))))
                           (map vector variances* poly1 poly2)))
            *sub-current-seen*
            (fail! s t)))

        (r/Protocol? s)
        (if (= (c/RClass-of Object) t)
          *sub-current-seen*
          (fail! s t))
        
        (r/Protocol? t)
        (let [desc (protocol-descendants t)]
          (if (some #(subtype? s %) desc)
            *sub-current-seen*
            (fail! s t)))

        (and (r/DataType? s)
             (r/DataType? t))
        (subtype-datatypes-or-records s t)

;Not quite correct, datatypes have other implicit ancestors (?)
        (r/DataType? s)
        (subtype-datatype-record-on-left s t)
        (r/DataType? t)
        (subtype-datatype-record-on-right s t)

        ;values are subtypes of their classes
        (and (r/Value? s)
             (impl/checking-clojure?))
        (let [^Value s s]
          (if (nil? (.val s))
            (fail! s t)
            (subtype (apply c/In (c/RClass-of (class (.val s)))
                            (cond
                              ;keyword values are functions
                              (keyword? (.val s)) [(c/keyword->Fn (.val s))]
                              ;strings have a known length as a seqable
                              (string? (.val s)) [(r/make-ExactCountRange (count (.val s)))]))
                     t)))

        (and (r/Result? s)
             (r/Result? t))
        (subtype-Result s t)
        
        (and (r/PrimitiveArray? s)
             (r/PrimitiveArray? t))
        (subtype-PrimitiveArray s t)

        (r/PrimitiveArray? s)
        (subtype (r/PrimitiveArray-maker Object r/-any r/-any) t)
      
        (and (r/TypeFn? s)
             (r/TypeFn? t))
        (subtype-TypeFn s t)

        (and (r/RClass? s)
             (r/RClass? t))
        (p :subtype-RClass (subtype-RClass s t))

        (r/Extends? s)
        (let [^Extends s s]
          (if (and (some #(subtype? % t) (.extends s))
                   (not-any? #(subtype? % t) (.without s)))
            *sub-current-seen*
            (fail! s t)))

        (r/Extends? t)
        (let [^Extends t t]
          (if (and (some #(subtype? s %) (.extends t))
                   (not-any? #(subtype? s %) (.without t)))
            *sub-current-seen*
            (fail! s t)))

        (and (r/CountRange? s)
             (r/CountRange? t))
        (subtype-CountRange s t)

        :else (fail! s t)))))

(defn protocol-descendants [^Protocol p]
  {:pre [(r/Protocol? p)]
   :post [(every? r/Type? %)]}
  (let [protocol-var (resolve (.the-var p))
        _ (assert protocol-var (str "Protocol cannot be resolved: " (.the-var p)))
        exts (extenders @protocol-var)]
    (for [ext exts]
      (cond
        (class? ext) (c/RClass-of-with-unknown-params ext)
        (nil? ext) r/-nil
        :else (throw (Exception. (str "What is this?" ext)))))))

;[Type Type -> (IPersistentSet '[Type Type])]
(defn- subtype [s t]
  {:post [(set? %)]}
  #_(prn "subtype")
;  (if-let [hit (@subtype-cache (set [s t]))]
;    (do #_(prn "subtype hit")
;        hit)
    (let [res (p :subtype-top-subtypeA* (subtypeA* *sub-current-seen* s t))]
      ;(swap! subtype-cache assoc (set [s t]) res)
      res))

;[(IPersistentSet '[Type Type]) (Seqable Type) (Seqable Type) (Option Type)
;  -> (IPersistentSet '[Type Type])]
(defn subtypes*-varargs [A0 argtys dom rst]
  (loop [dom dom
         argtys argtys
         A A0]
    (cond
      (and (empty? dom) (empty? argtys)) A
      (empty? argtys) (fail! argtys dom)
      (and (empty? dom) rst)
      (if-let [A (subtypeA* A (first argtys) rst)]
        (recur dom (next argtys) A)
        (fail! (first argtys) rst))

      (empty? dom) (fail! argtys dom)
      :else
      (if-let [A (subtypeA* A0 (first argtys) (first dom))]
        (recur (next dom) (next argtys) A)
        (fail! (first argtys) (first dom))))))

(defn subtype-kwargs* [^KwArgs s ^KwArgs t]
  {:pre [((some-fn r/KwArgs? nil?) s)
         ((some-fn r/KwArgs? nil?) t)]}
  (if (= s t)
    *sub-current-seen*
    (u/nyi-error "subtype kwargs")))

;; simple co/contra-variance for ->
;[(IPersistentSet '[Type Type]) Function Function -> (IPersistentSet '[Type Type])]
(defn arr-subtype [A0 ^Function s ^Function t]
  {:pre [(r/Function? s)
         (r/Function? t)]}
  ;; top for functions is above everything
  (cond
    ;; top for functions is above everything
    (r/TopFunction? t) A0
    ;; the really simple case
    (and (not ((some-fn :rest :drest :kws) s))
         (not ((some-fn :rest :drest :kws) t)))
    (do
      (when-not (= (count (.dom s))
                   (count (.dom t)))
        (fail! s t))
      (-> *sub-current-seen*
        ((fn [A0]
           (reduce (fn [A* [s t]]
                     (subtypeA* A* s t))
                   A0
                   (map vector (.dom t) (.dom s)))))
        (subtypeA* (.rng s) (.rng t))))

    ;kw args
    (and (.kws s)
         (.kws t))
    (do
      (mapv subtype (.dom t) (.dom s))
      (subtype (.rng s) (.rng t))
      (subtype-kwargs* (.kws t) (.kws s)))

    (and (:rest s)
         (not ((some-fn :rest :drest) t)))
    (-> *sub-current-seen*
      (subtypes*-varargs (.dom t) (.dom s) (.rest s))
      (subtypeA* (.rng s) (.rng t)))

    (and (not ((some-fn :rest :drest) s))
         (:rest t))
    (fail! s t)

    (and (.rest s)
         (.rest t))
    (-> *sub-current-seen*
      (subtypes*-varargs (:dom t) (:dom s) (:rest s))
      (subtypeA* (:rest t) (:rest s))
      (subtypeA* (:rng s) (:rng t)))

    ;; handle ... varargs when the bounds are the same
    (and (:drest s)
         (:drest t)
         (= (-> s :drest :name)
            (-> t :drest :name)))
    (-> *sub-current-seen*
      (subtypeA* (-> t :drest :pre-type) (-> s :drest :pre-type))
      ((fn [A0] 
         (reduce (fn [A* [s t]]
                   (subtypeA* A* s t))
                 A0 (map vector (:dom t) (:dom s)))))
      (subtypeA* (:rng s) (:rng t)))
    :else (fail! s t)))

;[(IPersistentSet '[Type Type]) Function (Seqable Function) -> (Option (IPersistentSet '[Type Type]))]
(defn supertype-of-one-arr [A s ts]
  (some #(handle-failure 
           (arr-subtype A % s))
        ts))

(defn subtype-Result
  [{t1 :t ^FilterSet f1 :fl o1 :o flow1 :flow :as s}
   {t2 :t ^FilterSet f2 :fl o2 :o flow2 :flow :as t}]
  (cond
    ;trivial case
    (and (= f1 f2)
         (= o1 o2)
         (= flow1 flow2))
    (subtype t1 t2)

    ;we can ignore some interesting results
    (and (orep/EmptyObject? o2)
         (or (= f2 (fops/-FS fr/-top fr/-top))
             ; check :then, :else is top
             #_(and (= (.else f2) fr/-top)
                  (= (.then f1) (.then f2)))
             ; check :else, :then is top
             #_(and (= (.then f2) fr/-top)
                  (= (.else f1) (.else f2))))
         (= flow2 (r/-flow fr/-top)))
    (subtype t1 t2)

    (and (orep/EmptyObject? o2)
         (= f1 f2)
         (= flow2 (r/-flow fr/-top)))
    (subtype t1 t2)

    ;special case for (& (is y sym) ...) <: (is y sym)
    (and (fr/AndFilter? (:then f1))
         (fr/TypeFilter? (:then f2))
         (every? fops/atomic-filter? (:fs (:then f1)))
         (= 1 (count (filter fr/TypeFilter? (:fs (:then f1)))))
         (= fr/-top (:else f2))
         (= flow1 flow2 (r/-flow fr/-top)))
    (let [f1-tf (first (filter fr/TypeFilter? (:fs (:then f1))))]
      (if (= f1-tf (:then f2))
        (subtype t1 t2)
        (fail! t1 t2)))

    :else (fail! t1 t2)))

(defn subtype-TypeFn-app?
  [^TypeFn tfn ^TApp ltapp ^TApp rtapp]
  {:pre [(r/TypeFn? tfn)
         (r/TApp? ltapp)
         (r/TApp? rtapp)]}
  (every? (fn [[v l r]]
            (case v
              :covariant (subtypeA*? *sub-current-seen* l r)
              :contravariant (subtypeA*? *sub-current-seen* r l)
              :invariant (and (subtypeA*? *sub-current-seen* l r)
                              (subtypeA*? *sub-current-seen* r l))))
          (map vector (.variances tfn) (.rands ltapp) (.rands rtapp))))

(defmulti subtype-TApp? (fn [^TApp S ^TApp T] 
                          {:pre [(r/TApp? S)
                                 (r/TApp? T)]}
                          [(class (.rator S)) (class (.rator T))
                           (= (.rator S) (.rator T))]))

(defmethod subtype-TApp? [TypeFn TypeFn false]
  [S T]
  (subtypeA*? (conj *sub-current-seen* [S T]) (c/resolve-TApp S) (c/resolve-TApp T)))

(defmethod subtype-TApp? [TypeFn TypeFn true]
  [^TApp S T]
  (binding [*sub-current-seen* (conj *sub-current-seen* [S T])]
    (subtype-TypeFn-app? (.rator S) S T)))

(defmethod subtype-TApp? [r/TCAnyType Name false]
  [S T]
  (binding [*sub-current-seen* (conj *sub-current-seen* [S T])]
    (subtype-TApp? S (update-in T [:rator] c/resolve-Name))))

(defmethod subtype-TApp? [Name r/TCAnyType false]
  [S T]
  (binding [*sub-current-seen* (conj *sub-current-seen* [S T])]
    (subtype-TApp? (update-in S [:rator] c/resolve-Name) T)))

; for [Name Name false]
(prefer-method subtype-TApp? 
               [Name r/TCAnyType false]
               [r/TCAnyType Name false])

;same operator
(defmethod subtype-TApp? [Name Name true]
  [^TApp S T]
  (let [r (c/resolve-Name (.rator S))]
    (binding [*sub-current-seen* (conj *sub-current-seen* [S T])]
      (subtype-TApp? (assoc-in S [:rator] r)
                     (assoc-in T [:rator] r)))))

; only subtypes if applied to the same F
(defmethod subtype-TApp? [F F false] [S T] false)
(defmethod subtype-TApp? [F F true]
  [^TApp S T]
  (let [tfn (some (fn [[_ {{:keys [name]} :F :keys [^Bounds bnds]}]] 
                    (when (= name (.name ^F (.rator S)))
                      (.higher-kind bnds)))
                  free-ops/*free-scope*)]
    (when tfn
      (subtype-TypeFn-app? tfn S T))))

(defmethod subtype-TApp? :default [S T] false)

(defn subtype-TypeFn
  [^TypeFn S ^TypeFn T]
  (if (and (= (.nbound S) (.nbound T))
           (= (.variances S) (.variances T))
           (= (.bbnds S) (.bbnds T))
           (let [names (repeatedly (.nbound S) gensym)
                 sbody (c/TypeFn-body* names S)
                 tbody (c/TypeFn-body* names T)]
             (subtype? sbody tbody)))
    *sub-current-seen*
    (fail! S T)))

(defn subtype-PrimitiveArray
  [^PrimitiveArray s 
   ^PrimitiveArray t]
  (if (and ;(= (.jtype s) (.jtype t))
           ;contravariant
           (subtype? (.input-type t)
                     (.input-type s))
           ;covariant
           (subtype? (.output-type s)
                     (.output-type t)))
    *sub-current-seen*
    (fail! s t)))

(defn- subtype-datatype-record-on-left
  [{:keys [the-class] :as s} t]
  (if (some #(subtype? % t) (set/union #{(c/RClass-of Object)} 
                                       (or (@dtenv/DATATYPE-ANCESTOR-ENV the-class)
                                           #{})))
    *sub-current-seen*
    (fail! s t)))

(defn- subtype-datatype-record-on-right
  [s {:keys [the-class] :as t}]
  (if (some #(subtype? s %) (set/union #{(c/RClass-of Object)} 
                                       (or (@dtenv/DATATYPE-ANCESTOR-ENV the-class)
                                           #{})))
    *sub-current-seen*
    (fail! s t)))

(defn- subtype-datatypes-or-records
  [{cls1 :the-class poly1 :poly? :as s} 
   {cls2 :the-class poly2 :poly? :as t}]
  {:pre [(every? r/DataType? [s t])]}
  (if (and (= cls1 cls2)
           (every? (fn [[v l r]]
                     (case v
                       :covariant (subtypeA* *sub-current-seen* l r)
                       :contravariant (subtypeA* *sub-current-seen* r l)
                       :invariant (and (subtypeA* *sub-current-seen* l r)
                                       (subtypeA* *sub-current-seen* r l))))
                   (map vector (:variances s) poly1 poly2)))
    *sub-current-seen*
    (fail! s t)))

(defn class-isa? 
  "A faster version of isa?, both parameters must be classes"
  [s ^Class t]
  (.isAssignableFrom t s))

(defn- subtype-rclass
  [{variancesl :variances classl :the-class :as s}
   {variancesr :variances classr :the-class :as t}]
  (let [replacementsl (c/RClass-replacements* s)
        replacementsr (c/RClass-replacements* t)]
    (cond
      ;easy case
      (and (empty? variancesl)
           (empty? variancesr)
           (empty? replacementsl)
           (empty? replacementsr))
      (if (class-isa? classl classr)
        *sub-current-seen*
        (fail! s t)))))

; (Cons Integer) <: (Seqable Integer)
; (ancestors (Seqable Integer)

(defn- subtype-RClass-common-base 
  [{polyl? :poly? lcls-sym :the-class :as s}
   {polyr? :poly? rcls-sym :the-class :as t}]
  (let [{variances :variances} s]
    (and (= lcls-sym rcls-sym)
         (or (and (empty? polyl?) (empty? polyr?))
             (and (seq polyl?)
                  (seq polyr?)
                  (every? identity
                          (doall (map #(case %1
                                         :covariant (subtype? %2 %3)
                                         :contravariant (subtype? %3 %2)
                                         (= %2 %3))
                                      variances
                                      polyl?
                                      polyr?))))))))

;(IPersistentMap Class Class)
(def boxed-primitives
  {Byte/TYPE Byte
   Short/TYPE Short
   Integer/TYPE Integer
   Long/TYPE Long
   Float/TYPE Float
   Double/TYPE Double
   Character/TYPE Character
   Boolean/TYPE Boolean})

;[RClass RClass -> Boolean]
(defn coerse-RClass-primitive
  [s t]
  (cond
    ; (U Integer Long) <: (U int long)
    (and 
      (#{(c/RClass-of Integer) (c/RClass-of Long)} s)
      (#{(c/RClass-of 'int) (c/RClass-of 'long)} t))
    true

    :else
    (let [spcls (u/symbol->Class (:the-class s))
          tpcls (u/symbol->Class (:the-class t))
          scls (or (boxed-primitives spcls)
                   spcls)
          tcls (or (boxed-primitives tpcls)
                   tpcls)]
      (class-isa? scls tcls))))

(defn subtype-RClass
  [{polyl? :poly? :as s}
   {polyr? :poly? :as t}]
  ;(prn "subtype-RClass" (prs/unparse-type s) (prs/unparse-type t))
  (let [scls (r/RClass->Class s)
        tcls (r/RClass->Class t)]
    (cond
      (or
        ; use java subclassing
        (and (empty? polyl?)
             (empty? polyr?)
             (empty? (:replacements s))
             (empty? (:replacements t))
             (class-isa? scls tcls))

        ;same base class
        (and (= scls tcls)
             (subtype-RClass-common-base s t))

        ;one is a primitive, coerse
        (and (or (.isPrimitive scls)
                 (.isPrimitive tcls))
             (coerse-RClass-primitive s t))

        ;find a supertype of s that is the same base as t, and subtype of it
        (some #(when (r/RClass? %)
                 (and (= (:the-class t) (:the-class %))
                      (subtype-RClass-common-base % t)))
              (c/RClass-supers* s)))
      *sub-current-seen*

      ;try each ancestor

      :else (fail! s t))))

;subtype if t includes all of s. 
;tl <= sl, su <= tu
(defn subtype-CountRange
  [{supper :upper slower :lower :as s}
   {tupper :upper tlower :lower :as t}]
  (if (and (<= tlower slower)
           (if tupper
             (and supper (<= supper tupper))
             true))
    *sub-current-seen*
    (fail! s t)))

(defmacro sub [s t]
  `(subtype (parse-type '~s)
            (parse-type '~t)))
