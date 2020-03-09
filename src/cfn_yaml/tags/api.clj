(ns cfn-yaml.tags.api
  (:require [cfn-yaml.tags :as tags]))

(defn !Sub [string & bindings]
  (tags/->!Sub string (or bindings {})))

(defn !Ref [logicalName]
  (tags/->!Ref logicalName))

(defn !Cidr [ipBlock count cidrBits]
  (tags/->!Cidr ipBlock count cidrBits))

(defn !Base64 [valueToEncode]
  (tags/->!Base64 valueToEncode))

(defn !FindInMap [mapName topLevelKey secondLevelKey]
  (tags/->!FindInMap mapName topLevelKey secondLevelKey))

(defn !GetAtt [logicalNameOfResource attributeName]
  (tags/->!GetAtt logicalNameOfResource attributeName))

(defn !Join [delimiter list-of-values]
  (tags/->!Join delimiter list-of-values))
