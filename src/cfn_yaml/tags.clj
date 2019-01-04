(ns cfn-yaml.tags
  (:require [clojure.string :as str]
            [clj-yaml.core :as yaml])
  (:import (org.yaml.snakeyaml.nodes ScalarNode SequenceNode MappingNode NodeTuple Tag NodeId)
           (org.yaml.snakeyaml DumperOptions$ScalarStyle DumperOptions$FlowStyle)
           (org.yaml.snakeyaml.constructor Construct Constructor)
           (org.yaml.snakeyaml.representer Represent)))

(defrecord !Sub [string bindings]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

(defrecord !Ref [logicalName]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

(defrecord !Cidr [ipBlock count cidrBits]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

(defn constructors [get-constructor]
  (->> [[!Ref #(->!Ref (.getValue %))]
        [!Sub (fn [node]
               (if (= NodeId/scalar (.getNodeId node))
                 (->!Sub (.getValue node) {})
                 (->!Sub (-> node .getValue first .getValue)
                         (into {}
                               (map #(do [(-> % .getKeyNode .getValue) (let [value-node (.getValueNode %)]
                                                                         (.construct (get-constructor value-node) value-node))]))
                                (-> node .getValue second .getValue)))))]
        [!Cidr (fn [node] (apply ->!Cidr (map #(.construct (get-constructor %) %)
                                             (.getValue node))))]]
       (into {} (map (fn [[klass f]]
                       [(Tag. (.getSimpleName klass)) (reify org.yaml.snakeyaml.constructor.Construct
                                                        (construct [this node]
                                                          (f node)))])))))

(defn scalar-node [tag value & {:keys [style] :or {style DumperOptions$ScalarStyle/PLAIN}}]
  (ScalarNode. (if (string? tag)
                 (Tag. tag)
                 tag)
               value
               nil
               nil
               style))

(defn representers [represent-data]
  (->> [[!Ref #(scalar-node "!Ref" (:logicalName %))]
        [!Sub (fn [{:keys [string bindings]}]
               (if (empty? bindings)
                 (scalar-node (Tag. "!Sub") string)
                 (SequenceNode. (Tag. "!Sub")
                                [(scalar-node Tag/STR string)
                                 (MappingNode. Tag/MAP
                                               (for [[k v] bindings]
                                                 (NodeTuple. (represent-data k) (represent-data v)))
                                               DumperOptions$FlowStyle/BLOCK)]
                                DumperOptions$FlowStyle/BLOCK)))]
        [!Cidr #(SequenceNode. (Tag. "!Cidr")
                               [(scalar-node Tag/STR (:ipBlock %) :style DumperOptions$ScalarStyle/DOUBLE_QUOTED)
                                (scalar-node Tag/INT (str (:count %)))
                                (scalar-node Tag/INT (str (:cidrBits %)))]
                               DumperOptions$FlowStyle/FLOW)]]
       (into {} (map (fn [[klass f]]
                       [klass (reify org.yaml.snakeyaml.representer.Represent
                                (representData [this data]
                                  (f data)))])))))
