(ns re-db.notebooks.test
  (:require [mhuebert.clerk-cljs :refer [show-cljs]]))

(def !a (atom 0))

(show-cljs (inc @!a))

(inc @!a)