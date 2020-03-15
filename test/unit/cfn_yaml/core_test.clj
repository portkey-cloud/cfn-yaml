(ns unit.cfn-yaml.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [cfn-yaml.core :as sut]
            [cfn-yaml.tags.api :refer [!Sub !Ref !Base64 !Cidr]]
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
                                   {:uri (!Sub "arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:${ComputeGroup}-get-items/invocations")
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
      :FunctionName (!Sub "${ComputeGroup}-get-items")
      :Principal "apigateway.amazonaws.com"
      :SourceArn (!Sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${Api}/*/*/*")}}
    :DevStage
    {:Type "AWS::ApiGateway::Deployment"
     :Properties
     {:RestApiId (!Ref "Api")
      :StageName "dev"}}}})

(deftest parse
  (let [parsed-template (with-open [in (io/input-stream (io/resource "stack.yml"))]
                          (sut/parse in))]
    (is (= template
           parsed-template))
    (is (= (slurp (io/resource "stack.yml"))
           (sut/generate-string parsed-template)))))

(deftest validate-references
  (testing "Validate references within the template"
    (try
      (sut/parse "
Name: !Sub
  - www.${Domain}
  - { Domain: !Ref RootDomainName }
")
      (is false "Undefined reference should have been found")
      (catch clojure.lang.ExceptionInfo e
        (is (= #{"RootDomainName"} (:unresolved (ex-data e)))))))
  (testing "Exclude pseudo parameters"
    (is (= {:Outputs {:MyStacksRegion
                      {:Value (!Ref "AWS::Region")}}}
           (sut/parse "Outputs:
  MyStacksRegion:
    Value: !Ref \"AWS::Region\"")))))

(def tpl
  {:Parameters {:NamePrefix {:Type "String"}}
   :Resources (into {} (for [stage [:dev :test :prod]
                             :let [stage-name (name stage)]]
                         [stage-name {:Type "AWS::S3::Bucket"
                                      :Properties {:BucketName (!Sub (str "${NamePrefix}-" stage-name))}}]))})

(def tpl-string
  "Parameters:
  NamePrefix:
    Type: String
Resources:
  dev:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: !Sub '${NamePrefix}-dev'
  test:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: !Sub '${NamePrefix}-test'
  prod:
    Type: AWS::S3::Bucket
    Properties:
      BucketName: !Sub '${NamePrefix}-prod'
")

(deftest generate
  (testing "Example template rendering matches expected string"
    (is (= tpl-string (sut/generate-string tpl))))
  (testing "Output literal style for strings with newlines"
    (is (= "!Sub |-
  foo
  bar
"
           (sut/generate-string (!Sub "foo\nbar"))))))

(deftest base64
  (is (= {:UserData (!Base64 {"Fn::Sub" "moi\nhei"})}
         (sut/parse "UserData: !Base64
     \"Fn::Sub\": |
       moi
       hei"))))

(deftest cidr
  (is (= (!Cidr "192.168.0.0/24" 6 5)
         (sut/parse "!Cidr [ \"192.168.0.0/24\", 6, 5 ]"))))
