(ns clojure.core.typed.test.core
  (:import (clojure.lang Seqable ISeq ASeq IPersistentVector Atom IPersistentMap
                         Keyword ExceptionInfo Symbol Var))
  (:require [clojure.test :refer :all]
            [clojure.tools.analyzer :refer [ast]]
            [clojure.tools.analyzer.hygienic :refer [ast-hy]]
            [clojure.repl :refer [pst]]
            [clojure.pprint :refer [pprint]]
            [clojure.data :refer [diff]]
            [clojure.core.typed :as tc, :refer :all]
            [clojure.core.typed.init]
            (clojure.core.typed
             [utils :as u :refer [with-ex-info-handlers top-level-error?]]
             [current-impl :as impl]
             [check :as chk :refer [expr-type tc-t combine-props env+ update check-funapp]]
             [inst :as inst]
             [subtype :as sub]
             [type-rep :refer :all]
             [type-ctors :refer :all]
             [filter-rep :refer :all]
             [filter-ops :refer :all]
             [object-rep :refer :all]
             [path-rep :refer :all]
             [parse-unparse :refer :all]
             [constant-type :refer [constant-type]]
             [lex-env :refer :all]
             [promote-demote :refer :all]
             [frees :refer :all]
             [free-ops :refer :all]
             [cs-gen :refer :all]
             [cs-rep :refer :all])
            [clojure.core.typed.test.rbt]
            [clojure.core.typed.test.person]
            [clojure.tools.trace :refer [trace-vars untrace-vars
                                         trace-ns untrace-ns]]))

(defn subtype? [& rs]
  (impl/ensure-clojure)
  (apply sub/subtype? rs))

(defn check [& as]
  (impl/ensure-clojure)
  (apply chk/check as))

(defmacro is-cf [& args]
  `(is (do
         (cf ~@args)
         true)))

;Aliases used in unit tests
(defmacro declare-map-aliases []
  `(do
     (cf (clojure.core.typed/def-alias clojure.core.typed.test.core/MyName ~'(HMap :mandatory {:a (Value 1)})))
     (cf (clojure.core.typed/def-alias clojure.core.typed.test.core/MapName ~'(HMap :mandatory {:a clojure.core.typed.test.core/MyName})))
     (cf (clojure.core.typed/def-alias clojure.core.typed.test.core/MapStruct1 ~'(HMap :mandatory {:type (Value :MapStruct1) 
                                                                                        :a clojure.core.typed.test.core/MyName})))
     (cf (clojure.core.typed/def-alias clojure.core.typed.test.core/MapStruct2 ~'(HMap :mandatory {:type (Value :MapStruct2) 
                                                                                        :b clojure.core.typed.test.core/MyName})))
     (cf (clojure.core.typed/def-alias clojure.core.typed.test.core/UnionName ~'(U clojure.core.typed.test.core/MapStruct1 
                                                                                   clojure.core.typed.test.core/MapStruct2)))))

(defmacro is-with-aliases [& body]
  `(is (do (declare-map-aliases)
           ~@body)))

;(check-ns 'clojure.core.typed.test.deftype)

(deftest add-scopes-test
  (is (let [body (make-F 'a)]
        (= (add-scopes 0 body)
           body)))
  (is (let [body (make-F 'a)]
        (= (add-scopes 1 body)
           (Scope-maker body))))
  (is (let [body (make-F 'a)]
        (= (add-scopes 3 body)
           (-> body Scope-maker Scope-maker Scope-maker)))))

(deftest remove-scopes-test
  (is (let [scope (Scope-maker (make-F 'a))]
        (= (remove-scopes 0 scope)
           scope)))
  (is (let [body (make-F 'a)]
        (= (remove-scopes 1 (Scope-maker body))
           body))))

(deftest parse-type-test
  (is (= (Poly-body* '(x) (parse-type '(All [x] x)))
         (make-F 'x)))
  (is (= (Poly-body* '(x y) (parse-type '(All [x y] x)))
         (make-F 'x)))
  (is (= (Poly-body* '(x y) (parse-type '(All [x y] y)))
         (make-F 'y)))
  (is (= (Poly-body* '(a b c d e f g h i) (parse-type '(All [a b c d e f g h i] e)))
         (make-F 'e))))

(deftest parse-type-fn-test
  (is (= (parse-type '[nil * -> nil])
         (make-FnIntersection (make-Function () -nil -nil))))
  (is (= (parse-type '(All [x ...] [nil ... x -> nil]))
         (PolyDots* '(x) [no-bounds]
                    (make-FnIntersection (make-Function () -nil nil (DottedPretype-maker -nil 'x)))))))

(deftest poly-constructor-test
  (is (= (Poly-body*
           '(x)
           (Poly* '(x) [no-bounds]
                  (make-F 'x)
                  '(x)))
         (make-F 'x)))
  (is (= (Poly-body*
           '(x)
           (Poly* '(x)
                  [(Bounds-maker -nil -false nil)]
                  (make-F 'x)
                  '(x)))
         (make-F 'x)))
  (is (= (parse-type '(All [x x1 [y :< x] z] [x -> y]))
         (let [no-bounds-scoped (Bounds-maker
                                  (add-scopes 4 -any)
                                  (add-scopes 4 (Un))
                                  nil)]
           (Poly-maker 4
                   [no-bounds-scoped
                    no-bounds-scoped
                    (Bounds-maker 
                      (add-scopes 4 (B-maker 3))
                      (add-scopes 4 (Un))
                      nil)
                    no-bounds-scoped]
                   (add-scopes 4
                               (make-FnIntersection
                                 (make-Function [(B-maker 3)] (B-maker 1)
                                                nil nil)))
                   '(x x1 y z))))))
(defmacro sub? [s t]
  `(subtype? (parse-type '~s)
             (parse-type '~t)))

(deftest subtype-test
  (is (subtype? (parse-type 'Integer)
                (parse-type 'Integer)))
  (is (subtype? (parse-type 'Integer)
                (parse-type 'Object)))
  (is (not (sub? Object Integer)))
  (is (sub? Object Object))
  (is (subtype? (parse-type 'Integer)
                (parse-type 'Number)))
  (is (subtype? (parse-type '(clojure.lang.Seqable Integer))
                (parse-type '(clojure.lang.Seqable Integer))))
  (is (subtype? (parse-type '(clojure.lang.Seqable Integer))
                (parse-type '(clojure.lang.Seqable Number))))
  (is (not
        (subtype? (parse-type '(clojure.lang.Seqable Number))
                  (parse-type '(clojure.lang.Seqable Integer)))))
  (is (subtype? (parse-type '(clojure.lang.Cons Integer))
                (parse-type '(clojure.lang.Cons Number))))
  (is (subtype? (parse-type '(clojure.lang.Cons Integer))
                (parse-type '(clojure.lang.Seqable Number)))))

(deftest subtype-java-exceptions-test
  (is (subtype? (RClass-of IndexOutOfBoundsException nil)
                (RClass-of Exception nil))))

(deftest subtype-intersection
  (is (not (subtype? (RClass-of Seqable [-any])
                     (In (RClass-of Seqable [-any])
                         (make-CountRange 1))))))

(deftest subtype-Object
  (is (subtype? (RClass-of clojure.lang.IPersistentList [-any]) (RClass-of Object nil))))

(deftest subtype-hmap
  (is (not (subtype? (constant-type '{:a nil})
                     (constant-type '{:a 1}))))
  (is (subtype? (constant-type '{:a 1 :b 2 :c 3})
                (constant-type '{:a 1 :b 2}))))

(deftest subtype-poly
  (is (subtype? (parse-type '(All [x] (clojure.lang.ASeq x)))
                (parse-type '(All [y] (clojure.lang.Seqable y))))))

(deftest subtype-rec
  (is (subtype? (parse-type 'Integer)
                (parse-type '(Rec [x] (U Integer (clojure.lang.Seqable x))))))
  (is (subtype? (parse-type '(clojure.lang.Seqable (clojure.lang.Seqable Integer)))
                (parse-type '(Rec [x] (U Integer (clojure.lang.Seqable x))))))
  (is (not (subtype? (parse-type 'Number)
                     (parse-type '(Rec [x] (U Integer (clojure.lang.Seqable x)))))))
  (is (sub? (HMap :mandatory {:op (Value :if)
                  :test (HMap :mandatory {:op (Value :var)
                               :var clojure.lang.Var})
                  :then (HMap :mandatory {:op (Value :nil)})
                  :else (HMap :mandatory {:op (Value :false)})})
            (Rec [x] 
                 (U (HMap :mandatory {:op (Value :if)
                           :test x
                           :then x
                           :else x})
                    (HMap :mandatory {:op (Value :var)
                           :var clojure.lang.Var})
                    (HMap :mandatory {:op (Value :nil)})
                    (HMap :mandatory {:op (Value :false)})))))

  (is (sub? (Rec [x] (U Integer (clojure.lang.ILookup x x)))
            (Rec [x] (U Number (clojure.lang.ILookup x x))))))

(deftest trans-dots-test
  (is (= (inst/manual-inst (parse-type '(All [x b ...]
                                             [x ... b -> x]))
                           (map parse-type '(Integer Double Float)))
         (parse-type '[Integer Integer -> Integer])))
  (is (= (inst/manual-inst (parse-type '(All [x b ...]
                                             [b ... b -> x]))
                           (map parse-type '(Integer Double Float)))
         (parse-type '[Double Float -> Integer])))
  ;map type
  (is (= (inst/manual-inst (parse-type '(All [c a b ...]
                                             [[a b ... b -> c] (clojure.lang.Seqable a) (clojure.lang.Seqable b) ... b -> (clojure.lang.Seqable c)]))
                           (map parse-type '(Integer Double Float)))
         (parse-type '[[Double Float -> Integer] (clojure.lang.Seqable Double) (clojure.lang.Seqable Float) -> (clojure.lang.Seqable Integer)]))))

;return type for an expression f
(defmacro ety [f]
  `(do (impl/ensure-clojure)
     (-> (ast ~f) ast-hy check expr-type ret-t)))

(deftest tc-invoke-fn-test
  (is (subtype? (ety
                  ((clojure.core.typed/fn> [a :- Number, b :- Number] b)
                     1 2))
                (parse-type 'Number)))
  ; manual instantiation "seq"
  (is (subtype? (ety
                  ((clojure.core.typed/fn> [a :- (clojure.lang.Seqable Number), b :- Number] 
                                   ((clojure.core.typed/inst seq Number) a))
                     [1 2 1.2] 1))
                (parse-type '(clojure.core.typed/Option (I (clojure.lang.ISeq java.lang.Number) (CountRange 1))))))
  ; inferred "seq"
  (is (= (ety
           (clojure.core.typed/fn> [a :- (clojure.lang.Seqable Number), b :- Number] 
                           1))
         (make-FnIntersection
           (make-Function
             [(RClass-of Seqable [(RClass-of Number nil)]) (RClass-of Number nil)] 
             (-val 1)
             nil nil
             :filter (-FS -top -bot)
             :object -empty))))
  ; poly inferred "seq"
  ; FIXME pfn> NYI
  #_(is (= (ety
           (clojure.core.typed/pfn> [c] 
                            [[a :- (clojure.lang.Seqable c)] 
                             [b :- Number]] 
                            1))
         (let [x (make-F 'x)]
           (Poly* [(:name x)]
                  [no-bounds]
                  (make-FnIntersection
                    (make-Function
                      [(RClass-of Seqable [x]) (RClass-of Number)] 
                      (-val 1)
                      nil nil
                      :filter (-FS -top -bot)
                      :object -empty))
                  '(x)))))
  ;test invoke fn
  (is (subtype? (ety
                  ((clojure.core.typed/fn> [a :- (clojure.lang.Seqable Number), b :- Number] 
                                   (seq a))
                     [1 2 1.2] 1))
                (parse-type '(U nil (I (CountRange 1) (clojure.lang.ISeq Number))))))
  (is (subtype? (ety
                  ((clojure.core.typed/fn> [a :- (clojure.lang.IPersistentMap Any Number), b :- Number] 
                                   ((clojure.core.typed/inst get Number Nothing) a b))
                     (zipmap [1] [2]) 1))
                (parse-type '(U nil Number)))))

(deftest get-special-test
  (is (subtype? 
        (ety 
          (clojure.core.typed/fn> [a :- (HMap :mandatory {:a Number})]
                                  (get a :a)))
        (parse-type
          '(Fn ['{:a java.lang.Number} -> java.lang.Number 
                :filters {:then (is java.lang.Number 0 [(Key :a)]), 
                          :else (| (is (HMap :absent-keys #{(Value :a)}) 0) 
                                   (is (U nil false) 0 [(Key :a)]))} 
                :object {:path [(Key :a)], :id 0}])))))

(deftest truth-false-values-test
  (is (= (tc-t (if nil 1 2))
         (ret (-val 2) (-FS -top -bot) (->EmptyObject))))
  (is (= (tc-t (if false 1 2))
         (ret (-val 2) (-FS -top -bot) (->EmptyObject))))
  (is (= (tc-t (if 1 1 2))
         (ret (-val 1) (-FS -top -bot) (->EmptyObject)))))

(deftest empty-fn-test
  (is (= (tc-t (clojure.core/fn []))
         (ret (make-FnIntersection
                (Function-maker [] (make-Result -nil
                                            (-FS -bot -top)
                                            (->EmptyObject))
                            nil nil nil))
              (-FS -top -bot)
              (->EmptyObject))))
  (is (= (tc-t (fn [] 1))
         (ret (make-FnIntersection
                (Function-maker [] (make-Result (-val 1)
                                              (-FS -top -bot)
                                              (->EmptyObject))
                              nil nil nil))
              (-FS -top -bot)
              (->EmptyObject))))
  (is (= (tc-t (let []))
         (ret -nil (-FS -bot -top) (->EmptyObject)))))

(deftest path-test
  (is (= (tc-t (fn [a] (let [a 1] a)))
         (ret (make-FnIntersection
                (Function-maker [-any]
                              (make-Result (-val 1)
                                           (-FS -top -top)
                                           -empty)
                              nil nil nil))
              (-FS -top -bot) -empty)))
  (is (= (tc-t (let [a nil] a))
         (ret -nil (-FS -top -top) -empty))))

(deftest equiv-test
  ; 1 arity :else filter is always bot
  (is (= (tc-t (= 1))
         (ret (Un -true -false) (-FS -top -bot) -empty)))
  (is (= (tc-t (= 1 1))
         (tc-t (= 1 1 1 1 1 1 1 1 1 1))
         (ret (Un -true -false) (-FS -top -top) -empty)))
  (is (= (tc-t (= 'a 'b))
         (tc-t (= 1 2))
         (tc-t (= :a :b))
         (tc-t (= :a 1 'a))
         (ret (Un -true -false) (-FS -top -top) -empty)))
  (is (= (tc-t (= :Val (-> {:a :Val} :a)))
         (ret (Un -true -false) (-FS -top -top) -empty))))

(deftest name-to-param-index-test
  ;a => 0
  (is (= (tc-t 
           (clojure.core.typed/fn> [a :- (U (HMap :mandatory {:op (Value :if)})
                                            (HMap :mandatory {:op (Value :var)}))] 
                                   (:op a)))
         (ret 
           (parse-type 
             '(Fn [(U '{:op (Value :var)} '{:op (Value :if)}) -> (U ':var ':if) 
                   :filters {:then (is (U (Value :var) (Value :if)) 0 [(Key :op)]), 
                             :else (| (is (HMap :absent-keys #{(Value :op)}) 0) 
                                      (is (U nil false) 0 [(Key :op)]))} 
                   :object {:path [(Key :op)], :id 0}]))
           (-FS -top -bot)
           -empty))))

(deftest refine-test
  (is (= (tc-t 
           (clojure.core.typed/fn> [a :- (U (HMap :mandatory {:op (Value :if)})
                                            (HMap :mandatory {:op (Value :var)}))]
                           (when (= (:op a) :if) 
                             a)))
         (ret (make-FnIntersection
                (Function-maker
                    [(Un (-hmap {(-val :op) (-val :if)})
                         (-hmap {(-val :op) (-val :var)}))]
                    (make-Result (Un -nil (-hmap {(-val :op) (-val :if)}))
                                 (-FS (-and (-filter (-val :if) 0 [(->KeyPE :op)])
                                            (-not-filter (Un -false -nil) 0)
                                            (-filter (-hmap {(-val :op) (-val :if)}) 0))
                                           ; what are these filters doing here?
                                      (-or (-and (-filter (-val :if) 0 [(->KeyPE :op)])
                                                 (-filter (Un -false -nil) 0))
                                           (-not-filter (-val :if) 0 [(->KeyPE :op)])))
                                 -empty)
                    nil nil nil))
              (-FS -top -bot)
              -empty))))


#_(deftest dotted-infer-test
  (is-cf (map number? [1])))

(deftest check-invoke
  ; wrap in thunk to prevent evaluation (analyzer currently evaluates forms)
  (is (u/top-level-error-thrown? (cf (fn [] (symbol "a" 'b)))))
  (is (= (ety (symbol "a" "a"))
         (RClass-of clojure.lang.Symbol))))

(deftest check-do-test
  (is (= (ety (do 1 2))
         (-val 2))))

(deftest tc-var-test
  (is (= (tc-t seq?)
         (ret (make-FnIntersection
                (Function-maker [-any]
                              (make-Result (RClass-of 'boolean) 
                                           (-FS (-filter (RClass-of ISeq [-any]) 0)
                                                (-not-filter (RClass-of ISeq [-any]) 0))
                                           -empty)
                              nil nil nil))
              (-FS -top -top) -empty))))

(deftest heterogeneous-ds-test
  (is (not (subtype? (parse-type '(HMap :mandatory {:a (Value 1)}))
                     (RClass-of ISeq [-any]))))
  (is (not (subtype? (parse-type '(Vector* (Value 1) (Value 2)))
                     (RClass-of ISeq [-any]))))
  (is (subtype? (parse-type '(Seq* (Value 1) (Value 2)))
                (RClass-of ISeq [-any])))
  (is (subtype? (parse-type '(List* (Value 1) (Value 2)))
                (RClass-of ISeq [-any])))
  (is (= (tc-t [1 2])
         (ret (-hvec [(-val 1) (-val 2)]) (-true-filter) -empty)))
  (is (= (tc-t '(1 2))
         (ret (HeterogeneousList-maker [(-val 1) (-val 2)]) (-true-filter) -empty)))
  (is (= (tc-t {:a 1})
         (ret (-complete-hmap {(-val :a) (-val 1)}) (-true-filter) -empty)))
  (is (= (tc-t {})
         (ret (-complete-hmap {}) (-true-filter) -empty)))
  (is (= (tc-t [])
         (ret (-hvec []) (-true-filter) -empty)))
  (is (= (tc-t '())
         (ret (HeterogeneousList-maker []) (-true-filter) -empty)))
  (is-cf '(a b) (List* clojure.lang.Symbol clojure.lang.Symbol)))

(deftest implied-atomic?-test
  (is (implied-atomic? (-not-filter -false 'a)(-not-filter (Un -nil -false) 'a))))

(deftest combine-props-test
  (is (= (map set (combine-props [(->ImpFilter (-not-filter -false 'a)
                                               (-filter -true 'b))]
                                 [(-not-filter (Un -nil -false) 'a)]
                                 (atom true)))
         [#{} #{(-not-filter (Un -nil -false) 'a)
                (-filter -true 'b)}])))

(deftest env+-test
  ;test basic TypeFilter
  ;update a from Any to (Value :a)
  (is (let [props [(-filter (-val :a) 'a)]
            flag (atom true)]
        (and (= (let [env {'a -any}
                      lenv (-PropEnv env props)]
                  (env+ lenv [] flag))
                (-PropEnv {'a (-val :a)} props))
             @flag)))
  ;test positive KeyPE
  ;update a from (U (HMap :mandatory {:op :if}) (HMap :mandatory {:op :var})) => (HMap :mandatory {:op :if})
  (is (let [props [(-filter (-val :if) 'a [(->KeyPE :op)])]
            flag (atom true)]
        (and (= (let [env {'a (Un (-hmap {(-val :op) (-val :if)})
                                  (-hmap {(-val :op) (-val :var)}))}
                      lenv (-PropEnv env props)]
                  (env+ lenv [] flag))
                (-PropEnv {'a (-hmap {(-val :op) (-val :if)})} props))
             @flag)))
  ;test negative KeyPE
  (is (let [props [(-not-filter (-val :if) 'a [(->KeyPE :op)])]
            flag (atom true)]
        (and (= (let [env {'a (Un (-hmap {(-val :op) (-val :if)})
                                  (-hmap {(-val :op) (-val :var)}))}
                      lenv (-PropEnv env props)]
                  (env+ lenv [] flag))
                (-PropEnv {'a (-hmap {(-val :op) (-val :var)})} props))
             @flag)))
  ;test impfilter
  (is (let [{:keys [l props]}
            (env+ (-PropEnv {'a (Un -false -true) 'b (Un -nil -true)}
                             [(->ImpFilter (-not-filter -false 'a)
                                           (-filter -true 'b))])
                  [(-not-filter (Un -nil -false) 'a)]
                  (atom true))]
        (and (= l {'a -true, 'b -true})
             (= (set props)
                #{(-not-filter (Un -nil -false) 'a)
                  (-filter -true 'b)}))))
  ; more complex impfilter
  (is-with-aliases (= (env+ (-PropEnv {'and1 (Un -false -true)
                                       'tmap (Name-maker 'clojure.core.typed.test.core/UnionName)}
                                      [(->ImpFilter (-filter (Un -nil -false) 'and1)
                                                    (-not-filter (-val :MapStruct1)
                                                                 'tmap
                                                                 [(->KeyPE :type)]))
                                       (->ImpFilter (-not-filter (Un -nil -false) 'and1)
                                                    (-filter (-val :MapStruct1)
                                                             'tmap
                                                             [(->KeyPE :type)]))])
                            [(-filter (Un -nil -false) 'and1)]
                            (atom true))))
  ; refine a subtype
  (is (= (:l (env+ (-PropEnv {'and1 (RClass-of Seqable [-any])} [])
                   [(-filter (RClass-of IPersistentVector [-any]) 'and1)]
                   (atom true)))
         {'and1 (RClass-of IPersistentVector [-any])}))
  ; bottom preserved
  (is (let [a (atom true)]
        (env+ (-PropEnv {'foo -any} []) [-bot] a)
        (false? @a))))

;FIXME all these tests relate to CTYP-24
(deftest destructuring-special-ops
  ;FIXME for destructuring rest args
;  (is (= (tc-t (let [a '(a b)]
;                 (seq? a)))
;         (ret -true (-true-filter) -empty)))
  (is (= (-> 
           (tc-t (let [a {:a 1}]
                   (if (seq? a)
                     (apply hash-map a)
                     a)))
           ret-t)
         (-complete-hmap {(-val :a) (-val 1)})))
  (is (= (tc-t (clojure.core.typed/fn> [{a :a} :- (HMap :mandatory {:a (Value 1)})]
                               a))
         (ret (make-FnIntersection 
                (Function-maker [(-hmap {(-val :a) (-val 1)})]
                              (make-Result (-val 1) 
                                           (-FS -top -top)  ; have to throw out filters whos id's go out of scope
                                           ;(->Path [(->KeyPE :a)] 0) ; requires 'equivalence' filters
                                           -empty)
                              nil nil nil))
              (-FS -top -bot)
              -empty)))
  ;FIXME inferred filters are bit messy, but should be (-FS -bot (! Seq 0))
  #_(is-with-aliases (= (-> (tc-t (clojure.core.typed/fn> [a :- clojure.core.typed.test.core/UnionName]
                                   (seq? a)))
           ret-t)
         (make-FnIntersection
           (Function-maker [(Name-maker 'clojure.core.typed.test.core/UnionName)]
                         (make-Result -false 
                                      ;FIXME why isn't this (-FS -bot (-not-filter (RClass-of ISeq [-any]) 0)) ?
                                      (-FS -bot -top)
                                      -empty)
                         nil nil nil))))
  (is (= (tc-t (let [{a :a} {:a 1}]
                 a))
         (ret (-val 1) 
              (-FS -top -top) ; a goes out of scope, throw out filters
              -empty)))
  ;FIXME should be (-FS -bot (! ISeq 0))
  #_(is (= (tc-t (clojure.core.typed/fn> [a :- (HMap :mandatory {:a (Value 1)})]
                               (seq? a)))
         (ret (make-FnIntersection
                (Function-maker [(-hmap {(-val :a) (-val 1)})]
                              (make-Result -false (-false-filter) -empty)
                              nil nil nil))
              (-FS -top -bot)
              -empty)))
  ;roughly the macroexpansion of map destructuring
  ;FIXME
  #_(is (= (tc-t (clojure.core.typed/fn> 
                 [map-param :- clojure.core.typed.test.rbt-types/badRight]
                 (when (and (= :Black (-> map-param :tree))
                            (= :Red (-> map-param :left :tree))
                            (= :Red (-> map-param :left :right :tree)))
                   (let [map1 map-param
                         map1
                         (if (clojure.core/seq? map1)
                           (clojure.core/apply clojure.core/hash-map map1)
                           map1)

                         mapr (clojure.core/get map1 :right)
                         mapr
                         (if (clojure.core/seq? mapr)
                           (clojure.core/apply clojure.core/hash-map mapr)
                           mapr)

                         maprl (clojure.core/get mapr :left)
                         ;_ (print-env "maprl")
                         maprl
                         (if (clojure.core/seq? maprl)
                           (clojure.core/apply clojure.core/hash-map maprl)
                           maprl)]
                     maprl))))))
  ;destructuring a variable of union type
  (is (= (->
           (tc-t (clojure.core.typed/fn> [{a :a} :- (U (HMap :mandatory {:a (Value 1)})
                                                       (HMap :mandatory {:b (Value 2)}))]
                                         a))
           ret-t)
         (make-FnIntersection 
           (make-Function [(Un (-hmap {(-val :a) (-val 1)})
                               (-hmap {(-val :b) (-val 2)}))]
                          (Un (-val 1) -any))))))

(deftest Name-resolve-test
  (is-with-aliases (= (tc-t (clojure.core.typed/fn> [tmap :- clojure.core.typed.test.core/MyName]
                                                    ;call to (apply hash-map tmap) should be eliminated
                                                    (let [{e :a} tmap]
                                                      e)))
                      (ret (make-FnIntersection 
                             (Function-maker [(Name-maker 'clojure.core.typed.test.core/MyName)]
                                         (make-Result (-val 1) (-FS -top -top) -empty)
                                         nil nil nil))
                           (-FS -top -bot) -empty)))
  (is-with-aliases (= (tc-t (clojure.core.typed/fn> [tmap :- clojure.core.typed.test.core/MapName]
                                                    (let [{e :a} tmap]
                                                      (assoc e :c :b))))
                      (ret (make-FnIntersection (Function-maker [(Name-maker 'clojure.core.typed.test.core/MapName)]
                                                            (make-Result (-hmap {(-val :a) (-val 1)
                                                                                 (-val :c) (-val :b)})
                                                                         (-FS -top -bot) -empty)
                                                            nil nil nil))
                           (-FS -top -bot) -empty)))
  ; Name representing union of two maps, both with :type key
  (is-with-aliases (subtype? 
                     (-> (tc-t (clojure.core.typed/fn> [tmap :- clojure.core.typed.test.core/UnionName]
                                                       (:type tmap)))
                         ret-t)
                     (parse-type '[clojure.core.typed.test.core/UnionName -> (U (Value :MapStruct2)
                                                                                (Value :MapStruct1))])))
  ; using = to derive paths
  (is-with-aliases (subtype? 
                     (-> (tc-t (clojure.core.typed/fn> [tmap :- clojure.core.typed.test.core/UnionName]
                                                       (= :MapStruct1 (:type tmap))))
                         ret-t)
                     (make-FnIntersection 
                       (make-Function 
                         [(Name-maker 'clojure.core.typed.test.core/UnionName)]
                         (Un -false -true)
                         nil nil
                         :filter (let [t (-val :MapStruct1)
                                       path [(->KeyPE :type)]]
                                   (-FS (-and 
                                          (-filter (-hmap {(-val :type) (-val :MapStruct1)
                                                           (-val :a) (Name-maker 'clojure.core.typed.test.core/MyName)})
                                                   0)
                                          (-filter (-val :MapStruct1) 0 path)
                                          (-filter t 0 path))
                                        (-not-filter t 0 path)))))))
  ; using filters derived by =
  (is-with-aliases (subtype? (-> (tc-t (clojure.core.typed/fn> [tmap :- clojure.core.typed.test.core/UnionName]
                                                               (if (= :MapStruct1 (:type tmap))
                                                                 (:a tmap)
                                                                 (:b tmap))))
                                 ret-t)
                             (parse-type '[clojure.core.typed.test.core/UnionName -> clojure.core.typed.test.core/MyName])))
  ; following paths with test of conjuncts
  ;FIXME
  #_(is (= (tc-t (clojure.core.typed/fn> [tmap :- clojure.core.typed.test.core/UnionName]
                                         ; (and (= :MapStruct1 (-> tmap :type))
                               ;      (= 1 1))
                               (if (clojure.core.typed/print-filterset "final filters"
                                    (let [and1 (clojure.core.typed/print-filterset "first and1"
                                                 (= :MapStruct1 (-> tmap :type)))]
                                      (clojure.core.typed/print-env "first conjunct")
                                      (clojure.core.typed/print-filterset "second and1"
                                        (if (clojure.core.typed/print-filterset "second test"
                                              and1)
                                          (do (clojure.core.typed/print-env "second conjunct")
                                            (clojure.core.typed/print-filterset "third and1"
                                              (= 1 1)))
                                          (do (clojure.core.typed/print-env "fail conjunct")
                                            (clojure.core.typed/print-filterset "fail and1"
                                              and1))))))
                                 (do (clojure.core.typed/print-env "follow then")
                                   (assoc tmap :c :d))
                                 1)))
         (ret (make-FnIntersection (Function-maker [(Name-maker 'clojure.core.typed.test.core/UnionName)]
                              (let [t (Un (-val 1)
                                          (-hmap {(-val :type) (-val :MapStruct1)
                                                               (-val :c) (-val :d)
                                                               (-val :a) (Name-maker 'clojure.core.typed.test.core/MyName)}))]
                                (make-Result t (-FS -top -bot) -empty))
                              nil nil nil))
              (-FS -top -bot) -empty))))

;(tc-t (clojure.core.typed/fn> [[a :- Number]
;                       [b :- Number]]
;                      (if (= a 1)
;                        a
;                        )))
;
;(-> (tc-t (clojure.core.typed/fn> [[tmap :- clojure.core.typed.test.core/UnionName]]
;                          (= :MapStruct1 (-> tmap :type))
;                          (= 1 (-> tmap :a :a))))
;  :t :types first :rng :fl unparse-filter-set pprint)

;(->
;  (tc-t (clojure.core.typed/fn> [[a :- Number]
;                         [b :- Number]]
;;           (-FS (-and (-filter (-val 1) 'a)
;;                      (-filter (-val 2) 'b))
;;                (-or (-not-filter (-val 1) 'a)
;;                     (-not-filter (-val 2) 'b)
;;                     (-and (-not-filter (-val 1) 'a)
;;                           (-not-filter (-val 2) 'b))))
;          (clojure.core.typed/tc-pr-filters "final filters"
;            (let [and1 (clojure.core.typed/tc-pr-filters "first and1"
;                         (= a 1))]
;              (clojure.core.typed/tc-pr-env "first conjunct")
;;              (-FS (-and (-not-filter (Un -false -nil) and1)
;;                         (-filter (-val 2) 'b))
;;                   (-or (-filter (Un -false -nil) and1)
;;                        (-not-filter (-val 2) 'b)))
;              (clojure.core.typed/tc-pr-filters "second and1"
;                (if (clojure.core.typed/tc-pr-filters "second test"
;                      and1)
;                  (do (clojure.core.typed/tc-pr-env "second conjunct")
;                    (clojure.core.typed/tc-pr-filters "third and1"
;                      (= b 2)))
;                  (do (clojure.core.typed/tc-pr-env "fail conjunct")
;                    (clojure.core.typed/tc-pr-filters "fail and1"
;                      and1))))))))
;  :t :types first :rng :fl unparse-filter-set pprint)

(deftest update-test
  (is (= (update (Un (-hmap {(-val :type) (-val :Map1)})
                     (-hmap {(-val :type) (-val :Map2)}))
                 (-filter (-val :Map1) 'tmap [(->KeyPE :type)]))
         (-hmap {(-val :type) (-val :Map1)})))
  ;test that update resolves Names properly
  (is-with-aliases (= (update (Name-maker 'clojure.core.typed.test.core/MapStruct2)
                              (-filter (-val :MapStruct1) 'tmap [(->KeyPE :type)]))
                      (Un)))
  ;test that update resolves Names properly
  ; here we refine the type of tmap with the equivalent of following the then branch 
  ; with test (= :MapStruct1 (:type tmap))
  (is-with-aliases (= (update (Name-maker 'clojure.core.typed.test.core/UnionName)
                              (-filter (-val :MapStruct1) 'tmap [(->KeyPE :type)]))
                      (-hmap {(-val :type) (-val :MapStruct1) 
                              (-val :a) (Name-maker 'clojure.core.typed.test.core/MyName)})))
  (is-with-aliases (= (update (Name-maker 'clojure.core.typed.test.core/UnionName)
                              (-not-filter (-val :MapStruct1) 'tmap [(->KeyPE :type)]))
                      (-hmap {(-val :type) (-val :MapStruct2) 
                              (-val :b) (Name-maker 'clojure.core.typed.test.core/MyName)})))
  (is (= (update (Un -true -false) (-filter (Un -false -nil) 'a nil)) 
         -false)))

(deftest overlap-test
  (is (not (overlap -false -true)))
  (is (not (overlap (-val :a) (-val :b))))
  (is (overlap (RClass-of Number) (RClass-of Integer)))
  (is (not (overlap (RClass-of Number) (RClass-of clojure.lang.Symbol))))
  (is (not (overlap (RClass-of Number) (RClass-of String))))
  (is (overlap (RClass-of clojure.lang.Seqable [-any]) (RClass-of clojure.lang.IMeta [-any])))
  (is (overlap (RClass-of clojure.lang.Seqable [-any]) (RClass-of clojure.lang.PersistentVector [-any])))
  )

#_(def-alias SomeMap (U (HMap :mandatory {:a (Value :b)})
                      (HMap :mandatory {:b (Value :c)})))

(deftest assoc-test
  (is (= (tc-t (assoc {} :a :b))
         (ret (-complete-hmap {(-val :a) (-val :b)})
              (-FS -top -bot)
              -empty)))
  ;see `invoke-special` for assoc for TODO
  ;FIXME
  #_(is (= (-> (tc-t (-> (fn [m]
                         (assoc m :c 1))
                     (clojure.core.typed/ann-form [clojure.core.typed.test.core/SomeMap -> (U '{:a ':b :c '1}
                                                                         '{:b ':c :c '1})])))
           ret-t :types first :rng)
         (make-Result (Un (-hmap {(-val :a) (-val :b)
                                  (-val :c) (-val 1)})
                          (-hmap {(-val :b) (-val :c)
                                  (-val :c) (-val 1)}))
                      (-FS -top -bot)
                      -empty))))
         


(deftest check-get-keyword-invoke-test
  ;truth valued key
  (is (= (tc-t (let [a {:a 1}]
                 (:a a)))
         (ret (-val 1) (-FS -top -top) -empty)))
  ;false valued key, a bit conservative in filters for now
  (is (= (tc-t (let [a {:a nil}]
                 (:a a)))
         (ret -nil (-FS -top -top) -empty)))
  ;multiple levels
  (is (= (tc-t (let [a {:c {:a :b}}]
                 (-> a :c :a)))
         (ret (-val :b) (-FS -top -top) -empty)))
  (is (= (tc-t (clojure.core/get {:a 1} :a))
         (tc-t (clojure.lang.RT/get {:a 1} :a))
         ;FIXME
         #_(tc-t ({:a 1} :a))
         (tc-t (:a {:a 1}))
         (ret (-val 1)
              (-FS -top -top)
              -empty))))

(defn print-cset [cs]
  (into {} (doall
             (for [ms (:maps cs)
                   [k v] (:fixed ms)]
               [k
                [(str (unparse-type (:S v))
                      " << "
                      (:X v)
                      " << "
                      (unparse-type (:T v)))]]))))

(deftest promote-demote-test
  (is (= (promote-var (make-F 'x) '#{x})
         -any))
  (is (= (demote-var (make-F 'x) '#{x})
         (Bottom)))
  (is (= (promote-var (RClass-of clojure.lang.ISeq [(make-F 'x)]) '#{x})
         (RClass-of clojure.lang.ISeq [-any])))
  (is (= (demote-var (RClass-of clojure.lang.ISeq [(make-F 'x)]) '#{x})
         (RClass-of clojure.lang.ISeq [(Bottom)]))))

(deftest variances-test
  (is (= (fv-variances (make-F 'x))
         '{x :covariant}))
  (is (= (fv-variances -any)
         '{}))
  (is (= (fv-variances 
           (make-Function [] (RClass-of Atom [(make-F 'a) (make-F 'a)])))
         '{a :invariant}))
  (is (= (fv-variances 
           (make-Function [] (RClass-of Atom [-any (make-F 'a)])))
         '{a :covariant}))
  (is (= (fv-variances 
           (make-Function [] (RClass-of Atom [(make-F 'a) -any])))
         '{a :contravariant})))


(deftest fv-test
  (is (= (fv (make-F 'x))
         '#{x})))

(deftest fi-test
  (is (empty? (fi (make-F 'x)))))

(deftest cs-gen-test
  (is (= (cs-gen #{} ;V
                 (zipmap '[x y] (repeat no-bounds)) ;X
                 {} ;Y
                 (-val 1) ;S
                 (make-F 'x)) ;T
         (->cset [(make-cset-entry {'x (->c (-val 1) 'x -any no-bounds)
                                    'y (->c (Un) 'y -any no-bounds)})])))
  ;intersections correctly inferred
  (is (= (cs-gen '#{} {'x no-bounds} '{} 
                 (-hvec [(RClass-of Number)])
                 (In (RClass-of Seqable [(make-F 'x)]) (make-CountRange 1)))
         (->cset [(make-cset-entry {'x (->c (RClass-of Number) 'x -any no-bounds)})])))
;correct RClass ancestor inference
  (is (= (cs-gen #{} {'x no-bounds} {} 
                 (RClass-of IPersistentVector [(RClass-of Number)])
                 (RClass-of Seqable [(make-F 'x)]))
         (->cset [(make-cset-entry {'x (->c (RClass-of Number) 'x -any no-bounds)})]))))

(deftest subst-gen-test
  (let [cs (cs-gen #{} ;V
                   (zipmap '[x y] (repeat no-bounds)) ;X
                   {} ;Y
                   (-val 1) ;S
                   (make-F 'x))]
    (is (= (subst-gen cs #{} (make-F 'x))
           {'x (->t-subst (-val 1) no-bounds)
            'y (->t-subst (Un) no-bounds)}))))

(deftest infer-test
  (is (= (infer (zipmap '[x y] (repeat no-bounds)) ;tv env
                {}
                [(-val 1) (-val 2)] ;actual
                [(make-F 'x) (make-F 'y)] ;expected
                (make-F 'x)))) ;result
  (is (= (infer {'x no-bounds} ;tv env
                {}
                [(RClass-of IPersistentVector [(Un (-val 1) (-val 2) (-val 3))])] ;actual
                [(RClass-of Seqable [(make-F 'x)])] ;expected
                (RClass-of ASeq [(make-F 'x)])))) ;result
  (is (= (infer {'x no-bounds} ;tv env
                {}
                [(-hvec [(-val 1) (-val 2) (-val 3)])] ;actual
                [(RClass-of Seqable [(make-F 'x)])] ;expected
                (RClass-of ASeq [(make-F 'x)]))))) ;result

(deftest arith-test
  (is (subtype? (:t (tc-t (+)))
                (RClass-of Number)))
  (is (subtype? (:t (tc-t (+ 1 2)))
                (RClass-of Number)))
  ;wrap in thunks to prevent evaluation
  (is (u/top-level-error-thrown? (cf (fn [] (+ 1 2 "a")))))
  (is (u/top-level-error-thrown? (cf (fn [] (-)))))
  (is (u/top-level-error-thrown? (cf (fn [] (/))))))

(deftest tc-constructor-test
  (is (= (tc-t (Exception. "a"))
         (ret (RClass-of Exception)
              (-FS -top -bot)
              (->EmptyObject)))))

(deftest tc-throw-test
  (is (subtype? (:t (tc-t (fn [] (throw (Exception. "a")))))
                (make-FnIntersection
                  (make-Function [] (Un))))))

(deftest first-seq-test
  (is (subtype? (ret-t (tc-t (first [1 1 1])))
                (Un -nil (RClass-of Number))))
  (is (subtype? (In (RClass-of clojure.lang.PersistentList [-any])
                    (make-CountRange 1))
                (In (RClass-of Seqable [-any])
                    (make-CountRange 1))))
  (is (subtype? (ret-t (tc-t (let [l [1 2 3]]
                               (if (seq l)
                                 (first l)
                                 (throw (Exception. "Error"))))))
                (RClass-of Number)))
  (is (= (tc-t (first [1]))
         (ret (-val 1))))
  (is (= (tc-t (first []))
         (ret -nil)))
  (is (subtype? (ret-t (tc-t (first [1 2 3])))
                (RClass-of Number))))

(deftest intersection-maker-test
  (is (= (In -nil (-val 1))
         (Un)))
  (is (= (In (RClass-of Seqable [-any])
             -nil)
         (Un))))
;FIXME
;  (is (= (In (RClass-of Number)
;             (RClass-of Symbol))
;         (Un)))
;  (is (= (In (RClass-of clojure.lang.APersistentMap [-any -any])
;             (RClass-of clojure.lang.Sorted))
;         (make-Intersection 
;           #{(RClass-of clojure.lang.APersistentMap [-any -any])
;             (RClass-of clojure.lang.Sorted)}))))
;

(deftest count-subtype-test
  (is (subtype? (make-CountRange 1)
                (make-CountRange 1)))
  (is (not (subtype? (make-CountRange 1)
                     (make-ExactCountRange 1))))
  (is (subtype? (make-ExactCountRange 1)
                (make-CountRange 1)))
  (is (subtype? (make-ExactCountRange 4)
                (make-CountRange 1)))
  (is (subtype? (make-ExactCountRange 4)
                (make-CountRange 0)))
  (is (subtype? (make-CountRange 2)
                (make-CountRange 1)))
  )


(deftest names-expansion-test
  (is (do
        (cf (clojure.core.typed/def-alias clojure.core.typed.test.core/MyAlias
              (U nil (HMap :mandatory {:a Number}))))
        (subtype? (Name-maker 'clojure.core.typed.test.core/MyAlias) 
                  -any))))

(deftest ccfind-test
  (is (subtype? (-> (tc-t (clojure.core.typed/fn> [a :- (clojure.lang.IPersistentMap Long String)]
                                          (find a 1)))
                  :t :types first :rng :t)
                (Un (-hvec [(RClass-of Long) (RClass-of String)])
                    -nil))))

(deftest map-infer-test
  (is (subtype? (ret-t (tc-t (map + [1 2])))
                (RClass-of Seqable [(RClass-of Number)])))
  (is (subtype? (ret-t (tc-t (map + [1 2] [1 2] [4 5] [6 7] [4 4] [3 4])))
                (RClass-of Seqable [(RClass-of Number)])))
  (is (u/top-level-error-thrown? (cf (map + [1 2] [1 2] [4 5] [6 7] [4 4] {3 4}))))
  (is (u/top-level-error-thrown? (cf (map + [1 2] [1 2] [4 5] [6 7] [4 4] #{'a 4})))))

(deftest ann-form-test
  (is (= (ret-t (tc-t 
                  (clojure.core.typed/ann-form (atom 1)
                                               (clojure.lang.Atom Number Number))))
         (parse-type '(clojure.lang.Atom Number Number)))))

(deftest atom-ops-test
  (is (subtype? (ret-t (tc-t
                         (reset!
                           (clojure.core.typed/ann-form (atom 1) 
                                                (clojure.lang.Atom Number Number))
                           10.1)))
                (RClass-of Number))))

(deftest apply-test
  ;conservative while not tracking keys "not" in a hmap
  (is (subtype? (ret-t (tc-t (apply merge [{:a 1}])))
                (Un -nil (RClass-of IPersistentMap [-any -any])))))

(deftest destructuring-test
  ;Vector destructuring with :as
  (is (= (ret-t (tc-t (let [[a b :as c] (clojure.core.typed/ann-form [1 2] (clojure.lang.Seqable Number))] 
                        [a b c])))
         (-hvec [(Un -nil (RClass-of Number))
                                 (Un -nil (RClass-of Number))
                                 (RClass-of Seqable [(RClass-of Number)])])))
  (is (= (ret-t (tc-t (let [[a b :as c] [1 2]] 
                        [a b c])))
         (-hvec [(-val 1)
                 (-val 2)
                 (-hvec [(-val 1) (-val 2)])])))
  ;Map destructuring of vector
  ;FIXME needs implementing, but gives a decent error msg
  #_(is (= (ret-t (tc-t (let [{a 0 b 1 :as c} [1 2]] 
                        [a b c])))
         (-hvec [(-val 1)
                 (-val 2)
                 (-hvec [(-val 1) (-val 2)])]))))

(deftest vararg-subtyping-test
  (is (subtype? (parse-type '[nil * -> nil])
                (parse-type '[nil -> nil])))
  (is-cf (clojure.core.typed/ann-form (clojure.core.typed/inst merge Any Any) [nil -> nil])))

(deftest poly-filter-test
  (is (= (ret-t (tc-t (let [a (clojure.core.typed/ann-form [1] (clojure.lang.IPersistentCollection clojure.core.typed/AnyInteger))]
                        (if (seq a)
                          (first a)
                          'a))))
         (parse-type '(U clojure.core.typed/AnyInteger (Value a))))))

(deftest type-fn-test 
  (is (= (with-bounded-frees [[(make-F 'm) (tfn-bound (parse-type '(TFn [[x :variance :covariant]] Any)))]]
           (check-funapp
             (-> (ast 'a) ast-hy) ;dummy
             [(-> (ast 1) ast-hy)];dummy
             (ret (parse-type '(All [x]
                                    [x -> (m x)])))
           [(ret -nil)]
           nil))
         (ret (TApp-maker (make-F 'm) [-nil])))))

;TODO how to handle casts. CTYP-12
;Also need tc-t to bind *delayed-errors*
#_(deftest prims-test
  (is (= (ret-t (tc-t (Math/sqrt 1)))
         (parse-type 'double))))

(deftest hmap-subtype
  (is-cf {} (clojure.lang.APersistentMap Any Any)))

;; `do` is special at the top level, tc-ignore should expand out to `do`
(tc-ignore
 (defprotocol some-proto (some-proto-method [_]))
 some-proto-method)

(deftest array-test
  (is (= (Class/forName "[I") 
         (class (into-array> int [1]))))
  (is (= (Class/forName "[Ljava.lang.Object;") 
         (class (into-array> (U nil int) [1]))))
  (is (= (Class/forName "[Ljava.lang.Number;") 
         (class (into-array> (U nil Number) [1]))))
  (is (= (Class/forName "[Ljava.lang.Object;") 
         (class (into-array> (U clojure.lang.Symbol Number) [1]))))
  (is (= (Class/forName "[Ljava.lang.Object;") 
         (class (into-array> Object (U clojure.lang.Symbol Number) [1]))))
  )

(deftest class-pathelem-test
  (is (= (-> (tc-t #(class %))
           ret-t :types first :rng Result-object*)
         (->Path [(->ClassPE)] 0)))
  (is (subtype? (-> (tc-t 
                      #(= Number (class %)))
                  ret-t)
                (FnIntersection-maker
                  [(make-Function
                     [-any]
                     (RClass-of 'boolean)
                     nil nil
                     :filter (-FS (-and (-filter (-val Number) 0 [(->ClassPE)])
                                        ;the important filter, updates first argument to be Number if predicate is true
                                        (-filter (RClass-of Number) 0))
                                  (-not-filter (-val Number) 0 [(->ClassPE)])))]))))

;TODO ^--
;(-> (tc-t #(let [a (class %)]
;             (if a
;               true
;               false)))
;  ret-t unparse-type)

(deftest map-literal-test
  (is-cf {:bar :b}
         '{:bar ':b})
  ;correctly generalise
  (is-cf {(clojure.core.typed/ann-form :bar clojure.lang.Keyword) :b}
         (clojure.lang.IPersistentMap clojure.lang.Keyword ':b)))

(deftest isa-test
  (is (tc-t (isa? 1 1)))
  (is (tc-t #(isa? (class %) Number))))

(deftest array-primitive-hint-test
  (is-cf (let [^ints a (clojure.core.typed/into-array> int [(int 1)])]
           (alength a))))

(deftest array-subtype-test
  (is (sub? (Array int) (Array int)))
  (is (sub? (Array int) (ReadOnlyArray int)))
  (is (sub? (Array Long) (clojure.lang.Seqable Long)))
  ;FIXME
  ;(is (not (sub? (Array Object) (clojure.lang.Seqable Long))))
  (is (not (sub? (ReadOnlyArray int) (Array int)))))

(deftest flow-assert-test
  (is (subtype?
        (-> (tc-t (fn [a]
                    {:pre [(integer? a)]}
                    a))
          ret-t)
        (parse-type '[Any -> clojure.core.typed/AnyInteger])))
  (is (subtype? 
        (-> (tc-t (let [a (read-string "1")
                        _ (assert (integer? a))]
                    (+ 10 a)))
          ret-t)
        (parse-type 'clojure.core.typed/AnyInteger)))
  ;postconditions
  (is (subtype?
        (-> (tc-t (fn [a]
                    {:post [(vector? %)]}
                    a))
          ret-t)
        (parse-type '[Any -> (clojure.lang.IPersistentVector Any)]))))

(deftest complete-hmap-test
  (is (subtype? (-complete-hmap {})
                (parse-type '(clojure.lang.APersistentMap Nothing Nothing))))
  (is (not
        (subtype? (-hmap {})
                  (parse-type '(clojure.lang.APersistentMap Nothing Nothing)))))
  (is (subtype? (-> (tc-t {}) ret-t)
                (parse-type '(clojure.lang.APersistentMap Nothing Nothing)))))

(deftest dotted-on-left-test
  (is-cf (memoize (fn []))))

(deftest string-as-seqable-test
  (is (subtype? 
        (RClass-of String)
        (RClass-of Seqable [-any])))
  (is (subtype? 
        (-val "a")
        (RClass-of Seqable [-any])))
  (is-cf (seq "a"))
  (is-cf (first "a") Character)
  (is-cf (first (clojure.core.typed/ann-form "a" String)) (clojure.core.typed/Option Character)))

(deftest recursive-cf-test
  (is (thrown? Exception
               (cf (clojure.core.typed/cf 1 Number)
                   Any))))

(deftest top-function-subtype-test
  (is (subtype? (parse-type '[Any -> Any])
                (parse-type 'AnyFunction))))

(deftest intersection-simplify-test
  (is-cf (let [a (clojure.core.typed/ann-form [] (U (Extends [Number] :without [(clojure.lang.IPersistentVector Number)])
                                                    (clojure.lang.IPersistentVector Number)))]
           (when (vector? a)
             a))
         (U nil (clojure.lang.IPersistentVector Number))))

(deftest kw-args-test
  (is (check-ns 'clojure.core.typed.test.kw-args)))

(deftest get-APersistentMap-test
  (is-cf (get (clojure.core.typed/ann-form {} (clojure.lang.APersistentMap Number Number)) :a)
         (U nil Number)))

(deftest enum-field-non-nilable-test
  (is-cf (java.util.concurrent.TimeUnit/NANOSECONDS)
         java.util.concurrent.TimeUnit))

;;;; Checking deftype implementation of protocol methods

(defmacro caught-top-level-errors [nfn & body]
  `(with-ex-info-handlers
     [top-level-error? (fn [data# _#]
                         (~nfn (count (:errors data#))))]
     ~@body
     false))

(deftest new-instance-method-return-test
  (is (check-ns 'clojure.core.typed.test.protocol))
  (is (caught-top-level-errors #{2}
        (check-ns 'clojure.core.typed.test.protocol-fail))))
;;;;

(deftest let-filter-unscoping-test
  (is-cf (fn [a]
            (and (< 1 2) a))
          [(U nil Number) -> Any :filters {:then (is Number 0)}]))

(deftest map-literal-containing-funapp-test
  (is-cf {:bar (identity 1)}))

(deftest doseq>-test
  (is-cf (clojure.core.typed/doseq> [a :- (U clojure.core.typed/AnyInteger nil), [1 nil 2 3]
                   :when a]
            (inc a)))
  ;wrap in thunk to prevent evaluation
  (is (u/top-level-error-thrown?
               (cf (fn []
                     (clojure.core.typed/doseq> [a :- (U clojure.core.typed/AnyInteger nil), [1 nil 2 3]]
                                                (inc a)))))))

(deftest for>-test
  (is-cf
    (clojure.core.typed/for> :- Number
                             [a :- (U nil Number), [1 nil 2 3]
                              b :- Number, [1 2 3]
                              :when a]
                             (+ a b))
    (clojure.lang.LazySeq Number))
  (is (u/top-level-error-thrown?
        (cf
          (clojure.core.typed/for> :- Number
                                   [a :- (U clojure.lang.Symbol nil Number), [1 nil 2 3]
                                    b :- Number, [1 2 3]]
                                   (+ a b))))))

(deftest dotimes>-test
  (is-cf (clojure.core.typed/dotimes> [i 100] (inc i)) nil))

(deftest records-test
  (is (check-ns 'clojure.core.typed.test.records))
  (is (check-ns 'clojure.core.typed.test.records2)))

(deftest string-methods-test
  (is-cf (.toUpperCase "a") String))

(deftest common-destructuring-test
  (is (check-ns 'clojure.core.typed.test.destructure)))

(deftest loop-errors-test
  (is (caught-top-level-errors #{1}
        (cf (loop [a 1] a))))
  (is (caught-top-level-errors #{2}
        (cf (clojure.core.typed/loop> [a :- String, 1] a)))))

(deftest map-indexed-test
  (is (cf (map-indexed (clojure.core.typed/inst vector clojure.core.typed/AnyInteger Long Any Any Any Any) 
                       [1 2])
          (clojure.lang.Seqable '[clojure.core.typed/AnyInteger Long]))))

(deftest letfn>-test
  (is (cf (clojure.core.typed/letfn> [a :- [Number -> Number]
                                      (a [b] b)]
            (a 1))
          Number))
  ;interdependent functions
  (is (cf (clojure.core.typed/letfn> [a :- [Number -> Number]
                                      (a [c] (b c))
                                      
                                      b :- [Number -> Number]
                                      (b [d] (do a d))]
            (a 1))
          Number)))

;FIXME convert datatypes+records to RClasses
(deftest protocol-untyped-ancestor-test
  (is (check-ns 'clojure.core.typed.test.protocol-untyped-extend)))

(deftest kw-args-fail-test
  (is (caught-top-level-errors #{1}
        (check-ns 'clojure.core.typed.test.kw-args-undeclared-fail))))

(deftest filter-combine-test
  (is (check-ns 'clojure.core.typed.test.filter-combine)))

(deftest or-filter-simplify-test
  ;(| (is T  'a)
  ;   (is T' 'a))
  ; simplifies to
  ;(is (U T T') 'a)
  (is (= (-or (-filter (RClass-of clojure.lang.Symbol) 'id)
              (-filter (RClass-of String) 'id))
         (-filter (Un (RClass-of clojure.lang.Symbol)
                      (RClass-of String))
                  'id))))

(deftest or-filter-update-test
  (is (= (update -any
                 (-or (-filter (RClass-of clojure.lang.Symbol) 'id)
                      (-filter (RClass-of String) 'id)))
         (Un (RClass-of clojure.lang.Symbol)
             (RClass-of String)))))

(deftest path-update-test
  (is (= (update (Un -nil (-hmap {(-val :foo) (RClass-of Number)}))
                 (-not-filter (Un -false -nil) 'id [(->KeyPE :foo)]))
         (-hmap {(-val :foo) (RClass-of Number)})))
  ; if (:foo a) is nil, either a has a :foo entry with nil, or no :foo entry
  ; TODO
  #_(is (= (update (-hmap {})
                 (-filter -nil 'id [(->KeyPE :foo)]))
         (make-HMap {} {(-val :foo) -nil}))))

(deftest multimethod-test
  (is (check-ns 'clojure.core.typed.test.mm)))

(deftest instance-field-test
  (is (cf (.ns ^clojure.lang.Var #'clojure.core/map))))

(deftest HMap-syntax-test
  (is (= (parse-type '(HMap :absent-keys #{':op}))
         (-hmap {} #{(-val :op)} true))))

(deftest map-filter-test
  (is (cf (clojure.core.typed/ann-form (fn [a] (:op a))
                                       [(U '{:op ':if} '{:op ':case})
                                        -> (U ':if ':case)
                                        :filters {:then (is (U ':case ':if) 0 [(Key :op)])
                                                  :else (| (is (HMap :absent-keys #{':op}) 0)
                                                           (is (U false nil) 0 [(Key :op)]))}
                                        :object {:id 0
                                                 :path [(Key :op)]}])))
  ; {:then (is :if 0 [:op])
  ;  :else (| (! :if 0 [:op])
  ;           (is (HMap :absent-keys #{:op}) 0))}
  (is (cf #(= :if (:op %))
          [(U '{:op ':if} '{:op ':case})
           -> Boolean
           :filters {:then (& (is '{:op (Value :if)} 0)
                              (is ':if 0 [(Key :op)]))
                     :else (! ':if 0 [(Key :op)])}]))
  (is (cf (clojure.core.typed/fn> [a :- (U '{:op ':if} '{:op ':case})
                b :- (U '{:op ':if} '{:op ':case})]
            (if (= :if (:op a))
              (= :case (:op b))
              false))))
  (is (cf (fn [a b] 
            (let [and__3941__auto__ (clojure.core/symbol? a)] 
              (if (clojure.core.typed/print-filterset "test" and__3941__auto__)
                (clojure.core/number? b) 
                and__3941__auto__))))))

(deftest warn-on-unannotated-vars-test
  (is (check-ns 'clojure.core.typed.test.warn-on-unannotated-var)))

(deftest number-ops-test
  (is (cf (min (Integer. 3) 10) Number)))

(defmacro throws-tc-error? [& body]
  `(with-ex-info-handlers
     [u/tc-error? (constantly true)]
     ~@body
     false))

(deftest ctor-infer-test
  (is (cf (java.io.File. "a")))
  (is (cf (let [a (or "a" "b")]
            (java.io.File. a))))
  (is (throws-tc-error?
        (cf (fn [& {:keys [path] :or {path "foo"}}]
            (clojure.core.typed/print-env "a")
            (java.io.File. path))
          [& {:path String} -> java.io.File]))))

;(fn> [a :- (U (Extends Number :without [(IPerVec Any)])
;              (Extends (IPV Any) :without [Number])
;              String)]
;  (cond
;    (number? a) ...
;    (vector? a) ...
;    :else ...))
;
;(Extends [(IPersistentCollection a)
;          (Seqable a)
;          (IPersistentVector a)
;          (Reversible a)
;          [Number -> a]
;          (IPersistentStack a)
;          (ILookup Number a)
;          (Associative Number a)
;          (Indexed a)]
;         :without [(IPersistentMap Any Any)])

(deftest extends-test
  ; without extends: never returns a (IPV Number) because we can have
  ; a type (I (IPM Any Any) (IPV Any))
  (is (u/top-level-error-thrown?
        (cf (fn [a]
              (if (vector? a)
                a
                nil))
            [(U (clojure.lang.IPersistentVector Number)
                (clojure.lang.IPersistentMap Any Any))
             -> (U nil (clojure.lang.IPersistentVector Number))])))
  ; can use assertions to prove non-overlapping interfaces
  (is (cf (fn [a]
            {:pre [(or (and (vector? a)
                            (not (map? a)))
                       (and (map? a)
                            (not (vector? a))))]}
            (if (vector? a)
              a
              nil))
          [(U (clojure.lang.IPersistentVector Number)
              (clojure.lang.IPersistentMap Any Any))
           -> (U nil (clojure.lang.IPersistentVector Number))]))
  ; or use static types
  (is (cf (fn [a]
            (if (vector? a)
              a
              nil))
          [(U (Extends [(clojure.lang.IPersistentVector Number)]
                       :without [(clojure.lang.IPersistentMap Any Any)])
              (Extends [(clojure.lang.IPersistentMap Any Any)]
                       :without [(clojure.lang.IPersistentVector Any)]))
           -> (U nil (clojure.lang.IPersistentVector Number))]))
  ; technically it's ok to implement Number and IPM
  (is (cf (fn [a]
            {:pre [(number? a)]}
            (clojure.core.typed/print-env "a")
            (+ 1 a))
          [(clojure.lang.IPersistentMap Any Any) -> Number])))

(deftest complete-hash-subtype-test
  (is (sub? (HMap :optional {} :complete? true)
            (clojure.lang.IPersistentMap Integer Long))))

(deftest set!-test
  (is (check-ns 'clojure.core.typed.test.set-bang)))

(deftest flow-unreachable-test
  ; this will always throw a runtime exception, which is ok.
  (is (cf (fn [a] 
            {:pre [(symbol? a)]}
            (clojure.core.typed/print-env "a") 
            (clojure.core.typed/ann-form a clojure.lang.Symbol))
          [Long -> clojure.lang.Symbol])))

; FIXME this is wrong, should not just be nil
#_(deftest array-first-test
  (is (cf (let [a (clojure.core.typed/into-array> Long [1 2])]
            (first a)))))

(deftest every?-update-test
  (is (cf (let [a (clojure.core.typed/ann-form [] (U nil (clojure.lang.IPersistentCollection Any)))]
            (assert (every? number? a))
            a)
          (U nil (clojure.lang.IPersistentCollection Number)))))

(deftest keys-vals-update-test
  (is (= (update (RClass-of IPersistentMap [-any -any])
                 (-filter (RClass-of Seqable [(RClass-of Number)])
                          'a [(->KeysPE)]))
         (RClass-of IPersistentMap [(RClass-of Number) -any])))
  ; test with = instead of subtype to catch erroneous downcast to (IPersistentMap Nothing Any)
  (is (= (tc-t (let [m (clojure.core.typed/ann-form {} (clojure.lang.IPersistentMap Any Any))]
                 (assert (every? number? (keys m)))
                 m))
         (ret (parse-type '(clojure.lang.IPersistentMap Number Any)))))
  (is (= (tc-t (let [m (clojure.core.typed/ann-form {} (clojure.lang.IPersistentMap Any Any))]
                 (assert (every? number? (vals m)))
                 m))
         (ret (parse-type '(clojure.lang.IPersistentMap Any Number)))))
  (is (= (tc-t (let [m (clojure.core.typed/ann-form {} (clojure.lang.IPersistentMap Any Any))]
                 (assert (every? number? (keys m)))
                 (assert (every? number? (vals m)))
                 m))
         (ret (parse-type '(clojure.lang.IPersistentMap Number Number)))))
  (is (cf (fn [m]
            {:pre [(every? number? (vals m))]}
            m)
          [(clojure.lang.IPersistentMap Any Any) -> (clojure.lang.IPersistentMap Any Number)]))
  (is (cf (fn [m]
            {:pre [(every? symbol? (keys m))
                   (every? number? (vals m))]}
            m)
          [(clojure.lang.IPersistentMap Any Any) -> (clojure.lang.IPersistentMap clojure.lang.Symbol Number)])))

; a sanity test for intersection cache collisions
(deftest intersect-cache-test
  (is (=
       (Poly-body*
         ['foo1 'foo2]
         (Poly* '[x y] 
                [no-bounds no-bounds]
                (In (make-F 'x) (make-F 'y))
                '[x y]))
       (In (make-F 'foo1) (make-F 'foo2)))))

(deftest latent-filter-subtype-test 
  (is (not (subtype? (parse-type '(Fn [Any -> Any :filters {:then (is Number 0)}]))
                     (parse-type '(Fn [Any -> Any :filters {:then (is Nothing 0)}]))))))

;TODO
;(deftest filter-seq-test
;  (is (cf (filter :a (clojure.core.typed/ann-form [] (clojure.lang.Seqable '{:b Number})))
;          ;FIXME why does this fail with an expected type?
;          (clojure.lang.Seqable '{:b Number :a Any})))
;  ;TODO
;  #_(is (cf (filter identity (clojure.core.typed/ann-form [] (clojure.lang.Seqable (U nil Number))))
;            (clojure.lang.Seqable Number))))

;TODO destructuring on records
;TODO does this instance lookup work? (cf (.the-class (->RClass ...)))
;TODO unmunge fields (.other-keys? hmap)
;TODO this is non-nil (last (take 100 (iterate update-without-plot initial-state)))
;TODO 
;          {final-grid :grid,
;           :as final-state} (last (take 100 (iterate update-without-plot initial-state)))
;          _ (assert final-state)
;          ; be smart enough to infer final-grid cannot be nil just from the above assertion.
;          _ (assert final-grid)

;TODO support (some #{...} coll)
;TODO (apply == (non-empty-seq))
