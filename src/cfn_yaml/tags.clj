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

(defrecord !Base64 [valueToEncode]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-findinmap.html
(defrecord !FindInMap [mapName topLevelKey secondLevelKey]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-getatt.html
(defrecord !GetAtt [logicalNameOfResource attributeName]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

;; https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference-join.html
(defrecord !Join [delimiter list-of-values]
  yaml/YAMLCodec
  (decode [data keywords]
    data)
  (encode [data]
    data))

(defn constructors [get-constructor]
  (let [construct #(.construct (get-constructor %) %)]
    (->> [[!Ref #(->!Ref (.getValue %))]
          [!Sub (fn [node]
                  (if (= NodeId/scalar (.getNodeId node))
                    (->!Sub (.getValue node) {})
                    (->!Sub (-> node .getValue first .getValue)
                            (into {}
                                  (map #(do [(-> % .getKeyNode .getValue) (construct (.getValueNode %))]))
                                  (-> node .getValue second .getValue)))))]
          [!Cidr (fn [node] (apply ->!Cidr (map construct (.getValue node))))]
          [!FindInMap (fn [node] (apply ->!FindInMap (map construct (.getValue node))))]
          [!GetAtt (fn [node] (apply ->!GetAtt (clojure.string/split (.getValue node) #"\.")))]
          [!Join (fn [node] (let [[delimiter list-of-values] (map construct (.getValue node))]
                             (->!Join delimiter list-of-values)))]
          [!Base64 (fn [node]
                     (condp = (.getNodeId node)
                       NodeId/scalar (->!Base64 (.getValue node))
                       NodeId/mapping (->!Base64 (into {}
                                                       (map (fn [node-tuple]
                                                              [(construct (.getKeyNode node-tuple))
                                                               (construct (.getValueNode node-tuple))]))
                                                       (.getValue node)))))]]
         (into {} (map (fn [[klass f]]
                         [(Tag. (.getSimpleName klass)) (reify org.yaml.snakeyaml.constructor.Construct
                                                          (construct [this node]
                                                            (f node)))]))))))

(defn scalar-node [tag value & {:keys [style] :or {style DumperOptions$ScalarStyle/PLAIN}}]
  (ScalarNode. (if (string? tag)
                 (Tag. tag)
                 tag)
               value
               nil
               nil
               style))

(defn representers [represent-data representer]
  (let [represent-map (fn [m & {:keys [tag] :or {tag Tag/MAP}}]
                        (MappingNode. tag
                                      (for [[k v] m]
                                        (NodeTuple. (represent-data k) (represent-data v)))
                                      DumperOptions$FlowStyle/BLOCK))]
    (->> [[!Ref #(scalar-node "!Ref" (:logicalName %))]
          [!Sub (fn [{:keys [string bindings]}]
                  (if (empty? bindings)
                    (scalar-node "!Sub" string :style (if (.contains string "\n")
                                                        DumperOptions$ScalarStyle/LITERAL
                                                        DumperOptions$ScalarStyle/PLAIN))
                    (SequenceNode. (Tag. "!Sub")
                                   [(scalar-node Tag/STR string) (represent-map bindings)]
                                   DumperOptions$FlowStyle/BLOCK)))]
          [!Cidr #(SequenceNode. (Tag. "!Cidr")
                                 [(scalar-node Tag/STR (:ipBlock %) :style DumperOptions$ScalarStyle/DOUBLE_QUOTED)
                                  (scalar-node Tag/INT (str (:count %)))
                                  (scalar-node Tag/INT (str (:cidrBits %)))]
                                 DumperOptions$FlowStyle/FLOW)]
          [!Join #(SequenceNode. (Tag. "!Join")
                                 [(.represent representer (:delimiter %))
                                  (SequenceNode. Tag/SEQ
                                                 (map (fn [x]
                                                        (if (string? x)
                                                          (scalar-node Tag/STR x :style DumperOptions$ScalarStyle/DOUBLE_QUOTED)
                                                          (.represent representer x)))
                                                      (:list-of-values %))
                                                 DumperOptions$FlowStyle/FLOW)]
                                 DumperOptions$FlowStyle/FLOW)]
          [!FindInMap #(SequenceNode. (Tag. "!FindInMap")
                                      [(scalar-node Tag/STR (:mapName %) :style DumperOptions$ScalarStyle/DOUBLE_QUOTED)
                                       (scalar-node Tag/STR (:topLevelKey %) :style DumperOptions$ScalarStyle/DOUBLE_QUOTED)
                                       (scalar-node Tag/STR (:secondLevelKey %) :style DumperOptions$ScalarStyle/DOUBLE_QUOTED)]
                                      DumperOptions$FlowStyle/FLOW)]
          [!GetAtt #(scalar-node "!GetAtt" (str (:logicalNameOfResource %) "." (:attributeName %)))]
          [!Base64 (fn [{:keys [valueToEncode]}]
                     (cond
                       (string? valueToEncode) (scalar-node "!Base64" valueToEncode)
                       (map? valueToEncode) (represent-map valueToEncode :tag (Tag. "!Base64"))))]]
         (into {} (map (fn [[klass f]]
                         [klass (reify org.yaml.snakeyaml.representer.Represent
                                  (representData [this data]
                                    (f data)))]))))))
