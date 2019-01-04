(ns cfn-yaml.tags.api
  (:require [cfn-yaml.tags :as tags]))

(defn !Sub [string & bindings]
  (tags/->!Sub string (or bindings {})))

(defn !Ref [logicalName]
  (tags/->!Ref logicalName))

(defn !Cidr [ipBlock count cidrBits]
  (tags/->!Cidr ipBlock count cidrBits))
