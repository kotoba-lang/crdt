(ns kotoba.crdt.kotoba-oracle-test
  "What keeps the shipped artifact honest, now that it is what runs.

  `clock-kotoba-parity-test` compiles `clock.kotoba` fresh and compares it to
  `clock.cljc`. That was the whole check while the host had its own copy of the
  Lamport rules. It is not the whole check any more, because for integer actors
  the host no longer computes them — it reads
  `resources/kotoba/crdt/oracle/clock.kir.edn`, and a fresh compile is not that
  file. Two things have to hold that did not have to before:

    1. the shipped artifact IS the current source, compiled
    2. the host actually reads it, rather than having quietly kept a copy

  The second is the one that is easy to lose and impossible to see: a
  delegation that fell back to a host implementation would pass every parity
  test ever written, because a host copy is exactly what those tests compare
  against. So this asks the only question that separates them — swap in a core
  that answers differently and see whether the host follows."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.crdt.clock :as clock]
            [kotoba.crdt.kotoba-oracle :as oracle]
            [kotoba.crdt.kotoba-oracle-gen :as gen]))

(deftest the-shipped-artifact-is-the-current-source-compiled
  (doseq [[id source] (sort-by key oracle/cores)]
    (testing (str id " <- " source)
      (let [shipped (edn/read-string (slurp (io/resource (oracle/resource-path id))))
            fresh (:kir (compiler/compile-source (slurp (io/file "src" source))
                                                 gen/target {}))]
        (is (= fresh shipped)
            (str "shipped KIR for " id " is stale — run `clojure -M:test:gen`"))))))

(deftest every-declared-core-actually-ships
  (doseq [id (keys oracle/cores)]
    (is (some? (io/resource (oracle/resource-path id)))
        (str "no artifact for " id))
    (is (some? (oracle/kir id)))))

(deftest a-missing-artifact-throws-rather-than-deciding-anything
  ;; The seam's one refusal. If it fell back instead, the first thing anyone
  ;; would notice is that a decision quietly stopped being the shipped one.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shipped decision core is missing"
                        (oracle/kir :not-a-core)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not declare that export"
                        (oracle/param-types :clock 'stamp))))

(def ^:private clock-record
  "[:record …] as `clock.kotoba` declares it. Spelled out HERE, unlike in
  `clock.cljc`, because the substitute core below has to declare the same
  types for the swap to be a swap and not a different module."
  "[:record :kotoba.crdt/clock [[:counter :i64] [:actor :i64]]]")

(def ^:private stamp-record
  "[:record :kotoba.crdt/stamp [[:counter :i64] [:actor :i64]]]")

(def ^:private wrong-clock-source
  "Same exports, same signatures, deliberately different answers: `tick` counts
  DOWN, and `before?` reports the opposite order."
  (str "(ns kotoba.crdt.clock (:export [init tick observe before?]))"
       "(defn init [actor :i64] " clock-record
       "  (record " clock-record " 0 actor))"
       "(defn tick [clock " clock-record "] " clock-record
       "  (record-assoc " clock-record " clock :counter"
       "    (- (record-get " clock-record " clock :counter) 1)))"
       "(defn observe [clock " clock-record " received " stamp-record "] " clock-record
       "  (record-assoc " clock-record " clock :counter 0))"
       "(defn before? [left " stamp-record " right " stamp-record "] :bool"
       "  (if (< (record-get " stamp-record " left :counter)"
       "         (record-get " stamp-record " right :counter))"
       "    false true))"))

(defn- with-core
  "Run `f` against a substituted core, then put the shipped one back."
  [kir f]
  (try
    (oracle/register-kir! :clock kir)
    (f)
    (finally (oracle/deregister-kir! :clock))))

(deftest the-host-reads-the-artifact-rather-than-keeping-a-copy
  (let [wrong (:kir (compiler/compile-source wrong-clock-source gen/target {}))
        a {:crdt/counter 0 :crdt/actor 0}
        b {:crdt/counter 1 :crdt/actor 0}]
    (testing "the shipped answers"
      (is (= 6 (:crdt/counter (clock/tick {:crdt/counter 5 :crdt/actor 1}))))
      (is (= 4 (:crdt/counter (clock/observe {:crdt/counter 3 :crdt/actor 1} a))))
      (is (true? (clock/before? a b)))
      (is (false? (clock/before? b a)))
      (is (false? (clock/before? a a))))
    (with-core wrong
      (fn []
        ;; A host that had kept `(update clock :crdt/counter inc)` and
        ;; `(< ca cb)` would answer exactly as it did above, and nothing else
        ;; in this repository would say so.
        (is (= 4 (:crdt/counter (clock/tick {:crdt/counter 5 :crdt/actor 1})))
            "tick followed the substituted core")
        (is (= 0 (:crdt/counter (clock/observe {:crdt/counter 3 :crdt/actor 1} a)))
            "observe followed the substituted core")
        (is (false? (clock/before? a b)) "before? followed it")
        (is (true? (clock/before? b a)) "and followed it in both directions")
        (is (true? (clock/before? a a)) "including where the tiebreak used to decide")
        (testing "everything built on the clock stamp follows with it"
          ;; `after?` and `max-stamp` have no Kotoba counterpart, but they are
          ;; expressed in terms of `before?`, so the substituted order reaches
          ;; them too. That is the shape a port is supposed to have: the rule
          ;; moved, the things phrased in terms of it did not have to.
          (is (true? (clock/after? a b)))
          (is (= a (clock/max-stamp a b))))))
    (testing "restored"
      (is (= 6 (:crdt/counter (clock/tick {:crdt/counter 5 :crdt/actor 1}))))
      (is (true? (clock/before? a b))))))

(deftest a-non-integer-actor-does-not-reach-the-guest
  ;; The stated boundary, as a test rather than a docstring. `clock.kotoba`
  ;; types actors `:i64`; this namespace has always taken any comparable id,
  ;; and `doc`'s README opens with (clock/init \"alice\"). Under a substituted
  ;; core that gets every integer-actor answer wrong, a string-actor clock is
  ;; expected to be UNCHANGED — which is the same statement as "these calls are
  ;; still answered by host code", said in the direction that can fail.
  (let [wrong (:kir (compiler/compile-source wrong-clock-source gen/target {}))
        alice {:crdt/counter 0 :crdt/actor "alice"}
        bob {:crdt/counter 0 :crdt/actor "bob"}]
    (with-core wrong
      (fn []
        (is (= {:crdt/counter 0 :crdt/actor "alice"} (clock/init "alice")))
        (is (= 1 (:crdt/counter (clock/tick alice))))
        (is (= 3 (:crdt/counter (clock/observe alice {:crdt/counter 2 :crdt/actor "bob"}))))
        (is (true? (clock/before? alice bob)))
        (is (false? (clock/before? bob alice)))
        (is (false? (clock/before? alice alice)))
        (testing "a mixed pair is host-answered too — one side is enough"
          ;; Counters chosen so the tiebreak is not reached: `compare` across a
          ;; Long and a String throws, here as it always has.
          (is (true? (clock/before? {:crdt/counter 0 :crdt/actor 0}
                                    {:crdt/counter 1 :crdt/actor "bob"}))))
        (testing "and so is a stamp that is missing the fields the guest needs"
          (is (= 1 (:crdt/counter (clock/observe {:crdt/counter 0 :crdt/actor 0} {}))))
          (is (true? (clock/before? {} {:crdt/counter 1 :crdt/actor 0}))))))))

(deftest a-clock-keeps-the-keys-a-host-hung-on-it
  ;; The guest record carries exactly two fields, so a delegation that returned
  ;; the guest's answer wholesale would drop anything else a host was tracking
  ;; on the same map. `tick` and `observe` merge instead.
  (let [c {:crdt/counter 1 :crdt/actor 7 :host/session "s-1"}]
    (is (= {:crdt/counter 2 :crdt/actor 7 :host/session "s-1"} (clock/tick c)))
    (is (= {:crdt/counter 5 :crdt/actor 7 :host/session "s-1"}
           (clock/observe c {:crdt/counter 4 :crdt/actor 9})))))

(deftest the-record-abi-is-read-out-of-the-artifact
  ;; `clock.cljc` writes neither record type down; it asks the shipped core for
  ;; the declared field order and builds positionally from that. Pinned here so
  ;; that a rename or a reordering in `clock.kotoba` shows up as this failing
  ;; rather than as records that no longer match their declared type.
  (is (= [[:record :kotoba.crdt/clock [[:counter :i64] [:actor :i64]]]]
         (oracle/param-types :clock 'tick)))
  (is (= [[:record :kotoba.crdt/clock [[:counter :i64] [:actor :i64]]]
          [:record :kotoba.crdt/stamp [[:counter :i64] [:actor :i64]]]]
         (oracle/param-types :clock 'observe)))
  (is (= [:i64] (oracle/param-types :clock 'init)))
  (is (= :bool (:result (oracle/signature :clock 'before?)))))
