(ns re-db.react
  (:require #?(:cljs ["react" :as react])
            #?(:cljs ["use-sync-external-store/with-selector" :as with-selector])
            [applied-science.js-interop :as j]
            [re-db.reactive :as r])
  #?(:cljs (:require-macros re-db.react)))

(defn useEffect [f deps] #?(:cljs (react/useEffect f deps)))
(defn useCallback [f deps] #?(:cljs (react/useCallback f deps)))
(defn useMemo [f deps] #?(:cljs (react/useMemo f deps)))
(defn useReducer [f init] #?(:cljs (react/useReducer f init)))
(defn useRef [init] #?(:cljs (react/useRef init)))
(defn useSyncExternalStoreWithSelector [subscribe get-snapshot server-snapshot selector isEqual]
  #?(:cljs
     (with-selector/useSyncExternalStoreWithSelector
      subscribe
      get-snapshot
      server-snapshot
      selector
      isEqual)))

#?(:cljs
   (defn use-deps
     "Wraps a value to pass as React `deps`, using a custom equal? check (default: clojure.core/=)"
     ([deps] (use-deps deps =))
     ([deps equal?]
      (let [counter (react/useRef 0)
            prev-deps (react/useRef deps)
            changed? (not (equal? deps (j/get prev-deps :current)))]
        (j/assoc! prev-deps :current deps)
        (when changed? (j/update! counter :current inc))
        (array (j/get counter :current))))))

(defmacro use-derefs
  ;; "safely" subscribe to arbitrary derefs
  [& body]
  `(let [!derefs# (~'re-db.react/useCallback (volatile! r/init-derefs) (cljs.core/array))
         out# (binding [r/*captured-derefs* (doto !derefs# (vreset! r/init-derefs))] ~@body)
         subscribe# (useCallback (fn [changed!#]
                                   (doseq [ref# @!derefs#] (add-watch ref# !derefs# (fn [_# _# o# n#] (changed!#))))
                                   #(doseq [ref# @!derefs#] (remove-watch ref# !derefs#)))
                                 (use-deps @!derefs#))] ;; re-subscribe when derefs change (by identity, not value)
     (useSyncExternalStoreWithSelector subscribe#
                                       #(mapv r/peek @!derefs#) ;; get-snapshot (reads values of derefs)
                                       nil ;; no server snapshot
                                       identity ;; we don't need to transform the snapshot
                                       =) ;; use Clojure's = for equality check
     out#))