(ns kotoba.crdt.kotoba-oracle
  "Runs the shipped decision cores.

  `src/kotoba/crdt/*.kotoba` holds the decisions;
  `resources/kotoba/crdt/oracle/*.kir.edn` is what was compiled from them and
  what ships. This namespace is the seam, and it is deliberately thin: it
  resolves a resource, executes an export, and decides nothing.

  ## Why this exists

  `clock.kotoba` landed with `clock-kotoba-parity-test`, which ran both
  implementations over the same inputs and required the same answers. That was
  the right first step and it is still here. But two implementations bound by a
  test are still two implementations, and the measure of the port is not how
  many host lines went away; it is whether the AUTHORITY moved. Until now it had
  not: the `.kotoba` was a checked replica and the `.cljc` was what ran. Now the
  `.kotoba` is what runs for the inputs it can express, and the `.cljc` keeps
  the halves that are not decisions — reading a map, naming a key, choosing a
  path.

  ## The guest ABI, and the two places the host has to meet it

  `kir/execute` takes a record as `[schema field …]` in DECLARED field order and
  returns the same shape, so `record` is a positional constructor and nothing
  more. The declared order is not written down here: `signature` reads it back
  out of the shipped artifact, which IS the `.kotoba` compiled, so callers get
  the order from the source of the rule rather than from a host copy of it.

  `:i64` is a JVM `long` under `:clj` and a `js/BigInt` under `:cljs`, and
  neither is what a `kotoba.crdt` host map holds. `i64` and `i64-value` are that
  conversion, kept here so a host never has to know which runtime it is on.

  ## No fallback around a missing artifact

  A missing or unreadable artifact throws. It does not quietly run something
  else, because a silent fallback is how a decision stops being the one that
  shipped.

  ## ClojureScript hosts must register the KIR

  There is no classpath to read a resource from, so `register-kir!` is the only
  way in and `kir` throws without it. That is a real narrowing of what this
  library used to do on that runtime and it is stated rather than discovered:
  see the boundary note on `kotoba.crdt.clock`."
  (:require [kotoba.kir :as kir]
            ;; Both only exist on the branch that has a classpath to read from.
            #?@(:clj [[clojure.edn :as edn]
                      [clojure.java.io :as io]])))

(def cores
  "Oracle id -> the .kotoba it was compiled from, under src/."
  {:clock "kotoba/crdt/clock.kotoba"})

(defn resource-path [id]
  (str "kotoba/crdt/oracle/" (name id) ".kir.edn"))

(def ^:private registered
  "Pre-parsed KIR, for runtimes with no classpath, and for the test that has to
  prove the host reads this rather than keeping its own copy."
  (atom {}))

(defn register-kir!
  "Install a parsed KIR for `id`, bypassing the resource read."
  [id kir]
  (swap! registered assoc id kir)
  kir)

(defn deregister-kir!
  "Drop a registration, so `id` reads the shipped artifact again."
  [id]
  (swap! registered dissoc id)
  nil)

(defn- read-artifact [id]
  #?(:clj
     (let [path (resource-path id)]
       (if-let [url (io/resource path)]
         (edn/read-string (slurp url))
         (throw (ex-info "shipped decision core is missing — run `clojure -M:test:gen`"
                         {:oracle id :path path}))))
     :cljs
     (throw (ex-info "no classpath on this runtime — register-kir! first"
                     {:oracle id}))))

(def ^:private cache (atom {}))

(defn kir
  "The shipped KIR for `id`, read once."
  [id]
  ;; A registration wins over the cache: it is an explicit instruction, and a
  ;; caller that registers after something already read the artifact means the
  ;; registration, not the read.
  (or (get @registered id)
      (get @cache id)
      (let [loaded (read-artifact id)]
        (swap! cache assoc id loaded)
        loaded)))

(defn signature
  "The shipped declaration of `export`: `:params`, `:param-types`, `:result`.

  This is how a host learns a record's field order without writing it down a
  second time. Throws if the export is not there, because a host asking for a
  signature is about to build an argument out of it."
  [id export]
  (let [export (symbol (name export))]
    (or (first (filter #(= export (:name %)) (:functions (kir id))))
        (throw (ex-info "shipped core does not declare that export"
                        {:oracle id :export export})))))

(defn param-types
  "Declared parameter types of `export`, in order."
  [id export]
  (:param-types (signature id export)))

(defn call
  "Execute an export of a shipped core. Args and result are guest ABI values;
  see `record`, `i64` and `i64-value` for the conversions."
  [id export args]
  (kir/execute (kir id) (symbol (name export)) (vec args)))

;; ── the guest values that are not plain host values ──────────────────

(defn record
  "Build a guest record argument: the descriptor, then fields in DECLARED
  order. Declared order, not map order — a record whose fields are permuted is
  not silently wrong, it simply fails to match the declared type. Pair with
  `record-fields` so the order comes from the artifact."
  [schema field-values]
  (into [schema] field-values))

(defn record-fields
  "`[[field type] …]` of a `[:record name fields]` descriptor, in declared
  order."
  [record-type]
  (nth record-type 2))

(defn record-values
  "Field values of a record `kir/execute` returned, in declared order."
  [record-value]
  (rest record-value))

(defn fits-i64?
  "Whether a host value can cross as `:i64` without changing what it means.

  On `:cljs` the guest speaks `js/BigInt` and the host speaks `js/Number`, so
  the round trip is only exact inside the safe-integer range; outside it the
  answer is no, and the caller keeps whatever it was going to do otherwise."
  [n]
  #?(:clj  (and (integer? n) (<= Long/MIN_VALUE n Long/MAX_VALUE))
     :cljs (and (number? n) (js/Number.isSafeInteger n))))

(defn i64
  "Host integer -> guest `:i64`."
  [n]
  #?(:clj (long n) :cljs (js/BigInt n)))

(defn i64-value
  "Guest `:i64` -> host integer."
  [n]
  #?(:clj n :cljs (js/Number n)))
