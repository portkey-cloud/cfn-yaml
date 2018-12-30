(ns unit.cfn-yaml.core-test
  (:require [clojure.test :refer :all]
            [cfn-yaml.core :as sut]
            [clojure.java.io :as io]))

(def template
  {:AWSTemplateFormatVersion "2010-09-09"
   :Description "Sample API"
   :Parameters {:ComputeGroup {:Type "String"
                               :Default "some-compute-group"}}
   :Resources
   {:Api
    {:Type "AWS::ApiGateway::RestApi"
     :Properties
     {:Body
      {:basePath "/"
       :info {:title "SampleApi"}
       :swagger "2.0"
       :schemes ["https"]
       :paths {"/get-items" {:get {:produces ["application/json"]
                                   :responses {}
                                   :x-amazon-apigateway-integration
                                   {:uri (sut/->Sub "arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:${ComputeGroup}-get-items/invocations")
                                    :responses {:default {:statusCode "200"}}
                                    :passthroughBehavior "when_no_match"
                                    :httpMethod "POST"
                                    :contentHandling "CONVERT_TO_TEXT"
                                    :type "aws_proxy"}}}}
       :x-amazon-apigateway-binary-media-types ["*/*"]}}}
    :ApiInvokePermission
    {:Type "AWS::Lambda::Permission"
     :Properties
     {:Action "lambda:InvokeFunction"
      :FunctionName (sut/->Sub "${ComputeGroup}-get-items")
      :Principal "apigateway.amazonaws.com"
      :SourceArn (sut/->Sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${Api}/*/*/*")}}
    :DevStage
    {:Type "AWS::ApiGateway::Deployment"
     :Properties
     {:RestApiId (sut/->Ref "Api")
      :StageName "dev"}}}})

(deftest parse
  (let [parsed-template (with-open [in (io/input-stream (io/resource "stack.yml"))]
                          (sut/parse in))]
    (is (= template
           parsed-template))
    (is (= (slurp (io/resource "stack.yml"))
           (sut/generate-string parsed-template)))))
