(ns re-db.integrations-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [clojure.walk :as walk]
            [datahike.api :as dh]
            [datalevin.core :as dl]
            [datomic.api :as dm]
            [re-db.api :as db]
            [re-db.hooks :as hooks]
            [re-db.in-memory :as mem]
            [re-db.integrations.datahike]
            [re-db.integrations.datalevin]
            [re-db.integrations.datomic]
            [re-db.integrations.in-memory]
            [re-db.memo :as memo]
            [re-db.triplestore :as ts]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.schema :as schema]))


(swap! read/!listeners empty)

(defn noids [e]
  (walk/postwalk (fn [x]
                   (if (:db/id x)
                     (dissoc x :db/id)
                     x)) e))

(do
  (def db-uuid (random-uuid))
  (def mem-conn (mem/create-conn))
  (def dm-conn (dm/connect (doto (str "datomic:mem://db-" db-uuid) dm/create-database)))
  (def dh-conn (let [config {:store {:backend :file :path "/tmp/example"}}]
                 (do (try (dh/delete-database config) (catch Exception e))
                     (try (dh/create-database config) (catch Exception e))
                     (dh/connect config))))
  (def dl-conn (dl/get-conn (str "/tmp/datalevin/db-" db-uuid) {}))

  (def databases
    [{:id :datomic :conn dm-conn}
     {:id :datahike :conn dh-conn}
     {:id :datalevin :conn dl-conn}
     {:id :in-memory :conn mem-conn}])


  (defn transact! [txs]
    (mapv
     #(read/transact! (:conn %) txs)
     databases))

  (def initial-data [{:movie/title "The Goonies"
                      :movie/genre "action/adventure"
                      :movie/release-year 1985
                      :movie/emotions [[:emotion/name "happy"]
                                       {:emotion/name "excited"}]}
                     {:movie/title "Commando"
                      :movie/genre "thriller/action"
                      :movie/release-year 1985
                      :movie/emotions [[:emotion/name "happy"]
                                       {:emotion/name "tense"}
                                       {:emotion/name "excited"}]}
                     {:movie/title "Repo Man"
                      :movie/genre "punk dystopia"
                      :movie/release-year 1984
                      :movie/emotions [[:emotion/name "happy"]
                                       [:emotion/name "sad"]]
                      :movie/top-emotion [:emotion/name "sad"]}
                     {:movie/title "Mr. Bean"}])

  (doseq [{:keys [conn]} databases]
    (ts/merge-schema conn {:movie/title (merge schema/string
                                               schema/one
                                               schema/unique-id)

                           :movie/genre (merge schema/string
                                               schema/one)

                           :movie/release-year (merge schema/long
                                                      schema/one)
                           :movie/emotions (merge schema/ref
                                                  schema/many)
                           :emotion/name (merge schema/string
                                                schema/one
                                                schema/unique-id)
                           :movie/top-emotion (merge schema/ref
                                                     schema/one)
                           }))
  (transact! (for [name ["sad"
                         "happy"
                         "angry"
                         "excited"
                         "tense"
                         "whatever"]]
               {:emotion/name name}))
  (transact! initial-data)
  nil

  )


(deftest entity-reverse

  (doseq [conn (map :conn databases)]
    (db/with-conn conn
      (is (string? (:emotion/name (first (db/where [:emotion/name])))))
      (is (= 1 (count (:movie/_emotions (db/entity [:emotion/name "tense"])))))
      (is (= "Commando"
             (-> [:emotion/name "tense"]
                 db/entity
                 :movie/_emotions
                 first
                 :movie/title))))))

;; a query-function that uses the read/entity api:



(deftest db-queries

  (memo/defn-memo $emo-movies [conn emo-name]
    (db/bound-reaction conn
                       (->> (db/entity [:emotion/name emo-name])
                            :movie/_emotions
                            (mapv :movie/title)
                            set)))

  (transact! initial-data)

  (r/session
   (let [sad-queries (mapv (fn [{:keys [id conn]}]
                             [id ($emo-movies conn "sad")])
                           databases)
         toggle-emotion! #(let [sad? (db/with-conn (:conn (first databases))
                                       (contains? (->> (db/get [:movie/title "Commando"] :movie/emotions)
                                                       (into #{} (map :emotion/name)))
                                                  "sad"))]
                            (transact! [[(if sad? :db/retract :db/add)
                                         [:movie/title "Commando"]
                                         :movie/emotions
                                         [:emotion/name "sad"]]]))
         !result (atom {})]

     (doseq [[id q] sad-queries]
       (add-watch q ::watch (fn [_ _ _ new] (swap! !result update id conj new)))
       @(r/reaction (swap! !result update id conj @q)))


     (toggle-emotion!)

     (is (apply = (vals @!result)))

     (toggle-emotion!)

     (is (apply = (vals @!result)))

     ;; watching our query, printing the value


     (doseq [[_ q] sad-queries] (r/dispose! q)))))

(deftest reads

  (doseq [{:keys [conn]} databases]
    (ts/merge-schema conn {:owner (merge schema/ref
                                         schema/unique-id
                                         schema/one)
                           :person/name (merge schema/unique-id
                                               schema/one
                                               schema/string)})
    (ts/transact conn [{:person/name "Fred"}])
    (ts/transact conn [{:owner [:person/name "Fred"]
                        :person/name "Ball"}]))

  (dl/update-schema dl-conn {:owner (merge schema/ref
                                           schema/unique-id
                                           schema/one)})

  (let [[dm-conn
         dh-conn
         dl-conn
         mem-conn] (map :conn databases)]
    (are [expr]
      (= (db/with-conn dm-conn expr)
         (db/with-conn dh-conn expr)
         (db/with-conn dl-conn expr)
         (db/with-conn mem-conn expr))

      (->> [:movie/title "Repo Man"]
           (db/pull '[*
                      (:movie/title :db/id true)
                      {:movie/top-emotion [(:emotion/name :db/id true)]}
                      {:movie/emotions [(:emotion/name :db/id true)]}])
           (#(update % :movie/emotions set)))

      (into #{} (map :person/name) (:_owner (db/entity [:person/name "Fred"])))
      (into #{} (map :person/name) (:_owner (db/pull '[:person/name :_owner] [:person/name "Fred"])))

      (->> [:movie/title "Mr. Bean"]
           (db/pull '[:movie/emotions]))))

  (let [[dm-conn
         dh-conn
         dl-conn
         mem-conn] (map :conn databases)]
    (are [expr]
      (= (db/with-conn dm-conn expr)
         (db/with-conn dh-conn expr)
         (db/with-conn dl-conn expr)
         (db/with-conn mem-conn expr))

      (->> [:movie/title "The Goonies"]
           (db/pull '[*])
           :movie/emotions
           (every? map?))

      (->> [:movie/title "The Goonies"]
           (db/pull '[*])
           :movie/emotions
           (map :movie/title))

      )))

(deftest attribute-resolvers
  (binding [read/*attribute-resolvers* {:movie/title-lowercase
                                        (fn [{:keys [movie/title]}]
                                          (str/lower-case title))}]
    (doseq [conn (map :conn databases)]
      (db/with-conn conn
        (is (= "the goonies"
               (->> [:movie/title "The Goonies"]
                    (db/pull [:movie/title-lowercase])
                    :movie/title-lowercase)))))))



(comment

 ;; pull api
 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull [:movie/title
                 {:movie/emotions [:emotion/name]}
                 {:movie/top-emotion [:emotion/name]}])))

 ;; aliases
 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull '[(:movie/title :as :title)
                  {(:movie/emotions :as :emos) [(:emotion/name :as :person/name)]}])))

 ;; :id option - use as lookup ref
 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull '[(:movie/title :db/id true)])))

 (db/with-conn (dm-db)
   (-> (db/entity [:movie/title "Repo Man"])
       (db/pull '[*
                  {:movie/top-emotion [*]}])))

 ;; logged lookups

 (db/with-conn (dm-db)
   (db/where [[:movie/title "Repo Man"]]))

 (db/with-conn (dm-db)
   (db/where [:movie/title]))

 (db/with-conn (dm-db)
   (read/get [:movie/title "Repo Man"] :movie/top-emotion))

 ;; filtering api
 (db/with-conn (dm-db)
   (db/where ;; the first clause selects a group of entities
    [:movie/title "Repo Man"]
    ;; subsequent clauses filter the initial set of entities
    [:movie/top-emotion [:emotion/name "sad"]]))

 (db/with-conn (dm-db)
   (->> (q/where [:movie/release-year 1985])
        (mapv deref)
        #_(q/pull [:movie/release-year :movie/title]))
   (read/captured-patterns))

 '(db/pull '[* {:sub/child [:person/name :birthdate]}])

 (let [a (r/atom 0)
       my-query (-> (db/bound-reaction dm-conn (doto (* @a 10) prn))
                    r/compute!)]
   (swap! a inc)
   (swap! a inc)
   (r/dispose! my-query))

 ;; testing with-let in a reaction
 (let [!log (atom [])
       log (fn [& args] (swap! !log conj args))
       a (r/atom 0)
       r (r/reaction!
          (hooks/with-let [_ (log :with-let/init @a)]
                          (doto @a (->> (log :with-let/body)))
                          (finally (log :with-let/finally))))]
   (swap! a inc)
   (swap! a inc)
   (log :with-let/value @r)
   (r/dispose! r)
   (swap! a inc)
   @!log))

(comment
 (let [a (r/atom 0)
       b (r/atom 0)
       effect (r/reaction!
               (hooks/use-effect (fn [] (prn :init) #(prn :dispose)) [@b])
               (prn :body @a))]
   (swap! a inc)
   (swap! a inc)
   (swap! b inc)
   (r/dispose! effect))

 (let [a (r/atom 0)
       b (r/atom 0)
       memo (r/reaction!
             (hooks/use-memo #(doto (rand-int 100) (->> (prn :init))) [@b])
             (prn :body)
             @a)]
   (swap! a inc)
   (swap! b inc)
   (r/dispose! memo)
   (swap! a inc) (swap! b inc)))

(comment
 (ts/merge-schema dm-conn {:person/name (merge schema/unique-id
                                               schema/one
                                               schema/string)
                           :pets (merge schema/ref
                                        schema/many)})
 (ts/transact dm-conn [{:db/id -1
                        :person/name "Mr. Porcupine"
                        :_pets {:db/id -2
                                :person/name "Sally"
                                :hair-color "brown"}}])
 )

(deftest ident-refs
  (is
   (apply =
          (for [{:keys [id conn]} databases]
            (db/with-conn conn
              (db/merge-schema! {:favorite-attribute (merge schema/ref
                                                            schema/one)
                                 :person/name {}
                                 :id (merge schema/unique-id
                                            schema/string
                                            schema/one)})
              (db/transact! [{:db/ident :person/name}
                             {:id "A"
                              :favorite-attribute :person/name}])
              [(-> (db/entity [:id "A"]) :favorite-attribute)
               (db/pull '[:favorite-attribute] [:id "A"])
               (db/pull '[*] [:id "A"])])))))

(deftest idents-in-where
  (db/with-conn dm-conn
    (db/merge-schema! {:service/commission (merge schema/ref schema/one)
                       :system/id (merge schema/unique-id schema/one schema/string)
                       :commission/name (merge schema/string schema/one)
                       :service/phase (merge schema/ref schema/one)
                       :phase/entry {}})
    (db/transact! [{:db/id "1"
                    :system/id "S"
                    :service/phase :phase/entry
                    :service/commission "2"}
                   {:db/id "2"
                    :system/id "C"
                    :commission/name "A commission"}])
    (is (some? (first (db/where [[:service/phase :phase/entry]
                                 [:service/commission [:system/id "C"]]]))))
    (is (some? (first (db/where [[:service/commission [:system/id "C"]]
                                 [:service/phase :phase/entry]]))))))

;; issues
;; - support for keywords-as-refs in re-db (? what does this even mean)
;; - support for resolving idents