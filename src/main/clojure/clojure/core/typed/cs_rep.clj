(ns clojure.core.typed.cs-rep
  (:refer-clojure :exclude [defrecord])
  (:require (clojure.core.typed
             [utils :as u]
             [type-rep :as r])
            [clojure.core.typed :as t])
  (:import (clojure.lang IPersistentMap Symbol Seqable)
           (clojure.core.typed.type_rep Bounds F)))

(t/ann-record t-subst [type :- r/TCType,
                       bnds :- Bounds])
(u/defrecord t-subst [type bnds]
  ""
  [(r/Type? type)
   (r/Bounds? bnds)])

(t/ann-record i-subst [types :- (Seqable r/TCType)])
(u/defrecord i-subst [types]
  ""
  [(every? r/Type? types)])

(t/ann-record i-subst-starred [types :- (Seqable r/TCType),
                               starred :- r/TCType])
(u/defrecord i-subst-starred [types starred]
  ""
  [(every? r/Type? types)
   (r/Type? starred)])

(t/ann-record i-subst-dotted [types :- (U nil (Seqable r/TCType)),
                              dty :- r/TCType,
                              dbound :- F])
(u/defrecord i-subst-dotted [types dty dbound]
  ""
  [(or (nil? types)
       (every? r/Type? types))
   (r/Type? dty)
   (r/F? dbound)])

(t/def-alias SubstRHS
  "The substitution records."
  (U t-subst i-subst i-subst-starred i-subst-dotted))

(t/def-alias SubstMap
  "A substutition map of symbols naming frees to types
  to instantitate them with."
  (IPersistentMap Symbol SubstRHS))

(t/ann ^:nocheck subst-rhs? (predicate SubstRHS))
(def subst-rhs? (some-fn t-subst? i-subst? i-subst-starred? i-subst-dotted?))

(t/ann ^:nocheck substitution-c? (predicate SubstMap))
(def substitution-c? (u/hash-c? symbol? subst-rhs?))

(t/ann-record c [S :- r/TCType,
                 X :- clojure.lang.Symbol,
                 T :- r/TCType,
                 bnds :- Bounds])
(u/defrecord c [S X T bnds]
  "A type constraint on a variable within an upper and lower bound"
  [(r/Type? S)
   (symbol? X)
   (r/Type? T)
   (r/Bounds? bnds)])

;; fixed : Listof[c]
;; rest : option[c]
;; a constraint on an index variable
;; the index variable must be instantiated with |fixed| arguments, each meeting the appropriate constraint
;; and further instantions of the index variable must respect the rest constraint, if it exists
(t/ann-record dcon [fixed :- (Seqable c)
                    rest :- (U nil c)])
(u/defrecord dcon [fixed rest]
  ""
  [(every? c? fixed)
   (or (nil? rest)
       (c? rest))])

(t/ann-record dcon-exact [fixed :- (Seqable c),
                          rest :- c])
(u/defrecord dcon-exact [fixed rest]
  ""
  [(every? c? fixed)
   (c? rest)])

(t/ann-record dcon-dotted [fixed :- (Seqable c),
                           dc :- c,
                           dbound :- F])
(u/defrecord dcon-dotted [fixed dc dbound]
  ""
  [(every? c? fixed)
   (c? dc)
   (r/F? dbound)])

(t/def-alias DCon (U dcon dcon-exact dcon-dotted))

(t/ann ^:nocheck dcon-c? (predicate DCon))
(def dcon-c? (some-fn dcon? dcon-exact? dcon-dotted?))

;; map : hash mapping index variables to dcons
(t/ann-record dmap [map :- (IPersistentMap Symbol DCon)])
(u/defrecord dmap [map]
  ""
  [((u/hash-c? symbol? dcon-c?) map)])

(t/ann-record cset-entry [fixed :- (IPersistentMap Symbol c),
                          dmap :- dmap,
                          projections :- Any])
(u/defrecord cset-entry [fixed dmap projections]
  ""
  [((u/hash-c? symbol? c?) fixed)
   (dmap? dmap)
   ((u/set-c? (u/hvector-c? (some-fn r/Type? r/Projection?)
                            (some-fn r/Type? r/Projection?)))
     projections)])

(t/ann make-cset-entry (Fn [(IPersistentMap Symbol c) -> cset-entry]
                           [(IPersistentMap Symbol c) (U nil dmap) -> cset-entry]
                           [(IPersistentMap Symbol c) (U nil dmap) Any -> cset-entry]))
(defn make-cset-entry
  ([fixed] (make-cset-entry fixed nil nil))
  ([fixed dmap] (make-cset-entry fixed dmap nil))
  ([fixed dmap projections] (->cset-entry fixed 
                                          (or dmap (->dmap {}))
                                          (or projections #{}))))

;; maps is a list of cset-entries, consisting of
;;    - functional maps from vars to c's
;;    - dmaps (see dmap.rkt)
;; we need a bunch of mappings for each cset to handle case-lambda
;; because case-lambda can generate multiple possible solutions, and we
;; don't want to rule them out too early
(t/ann-record cset [maps :- (Seqable cset-entry)])
(u/defrecord cset [maps]
  ""
  [(every? cset-entry? maps)])


;widest constraint possible
(t/ann no-constraint [Symbol Bounds -> c])
(defn no-constraint [v bnds]
  {:pre [(symbol? v)
         (r/Bounds? bnds)]}
  (->c (r/Union-maker #{}) v r/-any bnds))

(t/def-alias FreeBnds 
  "A map of free variable names to their bounds."
  (IPersistentMap Symbol Bounds))

;; Create an empty constraint map from a set of type variables X and
;; index variables Y.  For now, we add the widest constraints for
;; variables in X to the cmap and create an empty dmap.
(t/ann ^:nocheck empty-cset [FreeBnds FreeBnds -> cset])
(defn empty-cset [X Y]
  {:pre [(every? (u/hash-c? symbol? r/Bounds?) [X Y])]
   :post [(cset? %)]}
  (->cset [(->cset-entry (into {} (for [[x bnds] X] [x (no-constraint x bnds)]))
                         (->dmap {})
                         #{})]))

(t/ann ^:nocheck empty-cset-projection [FreeBnds FreeBnds Any -> cset])
(defn empty-cset-projection [X Y proj]
  {:pre [(every? (u/hash-c? symbol? r/Bounds?) [X Y])]
   :post [(cset? %)]}
  (->cset [(->cset-entry (into {} (for [[x bnds] X] [x (no-constraint x bnds)]))
                         (->dmap {})
                         #{proj})]))
