(ns kotoba.crdt.clock
  "Lamport logical clocks: {:crdt/counter n :crdt/actor id} stamps with a
  total order every replica agrees on regardless of message arrival order.
  Every primitive in kotoba.crdt uses a clock stamp as its merge tiebreak, so
  merge stays commutative, associative, and idempotent — the three properties
  that make convergence independent of network/delivery order.")

(defn init
  "A fresh clock for `actor` (any comparable id: a did:key, a session uuid...)."
  [actor]
  {:crdt/counter 0 :crdt/actor actor})

(defn tick
  "Advance the clock for a locally-originated event."
  [clock]
  (update clock :crdt/counter inc))

(defn stamp
  "The causal stamp to attach to an event originated at `clock`."
  [clock]
  (select-keys clock [:crdt/counter :crdt/actor]))

(defn observe
  "Advance `clock` to be strictly after a stamp received from another replica
  (Lamport's receive rule: local counter becomes max(local, received) + 1)."
  [clock received-stamp]
  (update clock :crdt/counter
          (fn [c] (inc (max c (:crdt/counter received-stamp 0))))))

(defn before?
  "Total order over stamps: counter first, actor id breaks ties. This is a
  deterministic tiebreak for concurrent events, not a causality test — two
  concurrent stamps still get a consistent (arbitrary) order every replica
  agrees on, which is exactly what LWW-Register merge needs."
  [a b]
  (let [ca (:crdt/counter a 0) cb (:crdt/counter b 0)]
    (or (< ca cb)
        (and (= ca cb) (neg? (compare (:crdt/actor a) (:crdt/actor b)))))))

(defn after? [a b] (before? b a))

(defn max-stamp [a b] (if (before? a b) b a))
