(ns cfn-yaml.core
  (:require [clj-yaml.core :as yaml]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [flatland.ordered.map :refer [ordered-map]]
            [cfn-yaml.tags :as tags])
  (:import (org.yaml.snakeyaml Yaml DumperOptions DumperOptions$FlowStyle)
           (org.yaml.snakeyaml.constructor Constructor)
           (org.yaml.snakeyaml.representer Representer)
           (java.util LinkedHashMap)))

(defn decode-key [s keywords]
  (cond
    (and (string? s) (.contains s "/")) s ;; / means namespace separator in keywords
    (and (string? s) keywords) (keyword s)
    :else s))

(extend-protocol yaml/YAMLCodec
  java.util.LinkedHashMap
  (decode [data keywords]
    (into (ordered-map)
          (for [[k v] data]
            [(-> k (yaml/decode keywords) (decode-key keywords)) (yaml/decode v keywords)])))
  clojure.lang.PersistentArrayMap
  (decode [data keywords]
    data)
  (encode [data]
    (let [lhm (LinkedHashMap.)]
      (doseq [[k v] data]
        (.put lhm (yaml/encode k) (yaml/encode v)))
      lhm)))

(defn make-yaml []
  (let [representers-field (-> Representer
                               .getSuperclass
                               .getSuperclass
                               (.getDeclaredField "representers"))
        yaml-constructors-field (-> Constructor
                                    .getSuperclass
                                    .getSuperclass
                                    (.getDeclaredField "yamlConstructors"))
        get-constructor (-> Constructor
                            .getSuperclass
                            .getSuperclass
                            (.getDeclaredMethod "getConstructor" (into-array Class [org.yaml.snakeyaml.nodes.Node])))
        represent-data (-> Representer
                           .getSuperclass
                           .getSuperclass
                           (.getDeclaredMethod "representData" (into-array [Object])))
        representer (Representer.)
        constructor (Constructor.)
        yaml (Yaml. constructor
                    representer
                    (doto (DumperOptions.)
                      (.setDefaultFlowStyle DumperOptions$FlowStyle/BLOCK)))]
    (doseq [accessible-object [representers-field yaml-constructors-field get-constructor represent-data]]
      (.setAccessible accessible-object true))
    (.putAll (.get yaml-constructors-field constructor)
             (tags/constructors #(.invoke get-constructor
                                          constructor
                                          (into-array org.yaml.snakeyaml.nodes.Node [%]))))
    (.putAll (.get representers-field representer)
             (tags/representers #(.invoke represent-data
                                          representer
                                          (into-array Object [%]))))
    yaml))

(defn find-references [template]
  (let [references (atom [])]
    (walk/postwalk #(when (or (= cfn_yaml.tags.!Sub (type %))
                              (= cfn_yaml.tags.!Ref (type %)))
                      (swap! references conj %)
                      %)
                   template)
    @references))

(defn find-referrables [template]
  (let [referrables (atom (concat (->> template :Parameters keys (map name))
                                  (->> template :Resources keys (map name))))]
    (walk/postwalk #(when (= cfn_yaml.tags.!Sub (type %))
                      (swap! referrables (fn [xs] (concat xs (keys (:bindings %)))))
                      %)
                   template)
    @referrables))

(defn validate-references [cfn]
  (let [referrables (set (find-referrables cfn))
        references (set (->> (find-references cfn)
                             (keep (fn [x]
                                     (condp = (type x)
                                       cfn_yaml.tags.!Ref (let [{:keys [logicalName]} x]
                                                            (if-not (.startsWith logicalName "AWS::")
                                                              [logicalName]
                                                              []))
                                       cfn_yaml.tags.!Sub (->> (re-seq #"\$\{([^\}]+)" (:string x))
                                                               (map second)
                                                               (filter #(not (.contains % "::")))))))
                             (apply concat)))
        unresolved-references (set/difference references referrables)]
    (when-not (empty? unresolved-references)
      (throw (ex-info (str "Unresolved references found: " unresolved-references)
                      {:unresolved unresolved-references})))))

(defn parse* [yml]
  (yaml/decode (.load (make-yaml) yml) true))

(defn parse [yml]
  (let [cfn (parse* yml)]
    (validate-references cfn)
    cfn))

(defn generate-string [cfn-data]
  (validate-references cfn-data)
  (.dump (make-yaml) (yaml/encode cfn-data)))

(defn parse-and-print [filename]
  (println (.dump (make-yaml) (yaml/encode (parse (slurp filename))))))
