(ns kotoba.crdt.register
  "LWW-Register: a single field that concurrent writers race on. Merge keeps
  whichever write has the later kotoba.crdt.clock stamp. This is the
  intentional, documented limit of this package: concurrent writes to the
  SAME field of the SAME entity are not merged character-by-character (no
  RGA/OT) — one write wins and the other is discarded. Concurrent writes to
  DIFFERENT fields (kotoba.crdt.doc) or DIFFERENT entities (kotoba.crdt.orset)
  never collide."
  (:require [kotoba.crdt.clock :as clock]))

(defn init [] nil)

(defn write [value stamp]
  {:crdt/value value :crdt/stamp stamp})

(defn value [register]
  (:crdt/value register))

(defn merge-register
  "Deterministic under any arrival order: the register with the later stamp
  always wins, whichever side of the merge it's passed as."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (clock/before? (:crdt/stamp a) (:crdt/stamp b)) b
    :else a))
