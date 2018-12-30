(ns cfn-yaml.core
  (:require [clj-yaml.core :as yaml]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [flatland.ordered.map :refer [ordered-map]])
  (:import [org.yaml.snakeyaml Yaml TypeDescription DumperOptions DumperOptions$FlowStyle DumperOptions$ScalarStyle]
           [org.yaml.snakeyaml.constructor Constructor]
           [org.yaml.snakeyaml.representer Representer BaseRepresenter]
           [org.yaml.snakeyaml.nodes Tag]
           [org.yaml.snakeyaml.nodes ScalarNode]))

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
            [(-> k (yaml/decode keywords) (decode-key keywords)) (yaml/decode v keywords)]))))

(defmacro deftag [name]
  `(defrecord ~name ~['value]
     yaml/YAMLCodec
     ~(list 'encode ['this]
        'this)
     (~'decode [~'this ~'keywords]
      (new ~name ~'value))))

(defmacro make-type-description [tag]
  `(proxy ~['TypeDescription] ~[tag (str "!" tag)]
     (~'newInstance ~['node]
      (new ~tag ~'(.getValue node)))))

(deftag Sub)
(deftag Ref)
(deftag Base64)

(defn represent-data [node-fn]
  (reify org.yaml.snakeyaml.representer.Represent
    (representData [this data]
      (node-fn data))))

(defn make-yaml []
  (let [representers-field (-> Representer
                               .getSuperclass
                               .getSuperclass
                               (.getDeclaredField "representers"))
        representer (Representer.)
        yaml (Yaml. (Constructor.) representer (doto (DumperOptions.)
                                                 (.setDefaultFlowStyle DumperOptions$FlowStyle/BLOCK)))]
    (.setAccessible representers-field true)
    (.putAll (.get representers-field representer)
             {Sub (represent-data #(ScalarNode. (Tag. "!Sub") (.-value %) nil nil DumperOptions$ScalarStyle/PLAIN))
              Ref (represent-data #(ScalarNode. (Tag. "!Ref") (.-value %) nil nil DumperOptions$ScalarStyle/PLAIN))
              Base64 (represent-data #(ScalarNode. (Tag. "!Base64") (.-value %) nil nil DumperOptions$ScalarStyle/PLAIN))})
    (.addTypeDescription yaml (make-type-description Sub))
    (.addTypeDescription yaml (make-type-description Ref))
    (.addTypeDescription yaml (make-type-description Base64))
    yaml))

(defn find-refs [template]
  (let [refs (atom [])]
    (walk/postwalk #(when (or (= cfn_yaml.core.Sub (type %))
                              (= cfn_yaml.core.Ref (type %)))
                      (swap! refs conj %)
                      %)
                   template)
    @refs))

(defn validate-references [cfn]
  (let [referrables (set (concat (->> cfn :Parameters keys (map name))
                                 (->> cfn :Resources keys (map name))))
        references (set (->> (find-refs cfn)
                             (keep (fn [x]
                                     (condp = (type x)
                                       cfn_yaml.core.Ref [(:value x)]
                                       cfn_yaml.core.Sub (->> (re-seq #"\$\{([^\}]+)" (:value x))
                                                               (map second)
                                                               (filter #(not (.contains % "::")))))))
                             (apply concat)))
        unresolved-references (set/difference references referrables)]
    (when-not (empty? unresolved-references)
      (throw (ex-info (str "Unresolved references found: " unresolved-references)
                      {:unresolved unresolved-references})))))

(defn parse [yml]
  (let [cfn (yaml/decode (.load (make-yaml) yml) true)]
    (validate-references cfn)
    cfn))

(defn generate-string [cfn-data]
  (validate-references cfn-data)
  (.dump (make-yaml) (yaml/encode cfn-data)))

(defn parse-and-print [filename]
  (println (.dump (make-yaml) (yaml/encode (parse (slurp filename))))))
