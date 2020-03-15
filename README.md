# cfn-yaml

Generate (and read) Cloudformation Yaml templates from Clojure data.

Also validates references in the templates.

## Status and motivation

[![CircleCI](https://circleci.com/gh/portkey-cloud/cfn-yaml.svg?style=svg)](https://circleci.com/gh/portkey-cloud/cfn-yaml)

Initial goal was to be able to parse Cloudformation Yaml templates that contain short for tags, for example `!Sub 'Hello ${AWS::StackName}'`, into Clojure data. These [tags](https://yaml.org/spec/1.1/#local%20tag/) need support from the parser, for which [snakeyaml](https://bitbucket.org/asomov/snakeyaml/wiki/Documentation#markdown-header-constructors-representers-resolvers) provides support via constructors and representers. This library implements constructors and representers for some short form tags: Sub, Ref, Cidr, Base64, GetAtt, Join, FindInMap.

## Usage

First, add the dependency to `deps.edn`:

```clojure
{:deps {portkey-cloud/cfn-yaml {:git/url "https://github.com/portkey-cloud/cfn-yaml"
                                :sha "79b3c975a25c14f12c41398863f1cb4a3521c4e7"}}}
```

Then hack in the repl :). The example below uses [aws-api](https://github.com/cognitect-labs/aws-api) for pushing the generated YAML to Cloudformation API, so the repl is started with a `AWS_PROFILE` containing credentials for making API calls to AWS.

```clojure
0% AWS_REGION=eu-west-1 AWS_PROFILE=tiuhti clj -A:aws:rebel
[Rebel readline] Type :repl/help for online help info
user=> (require '[cfn-yaml.core :as cfn])
nil
user=> (require '[cfn-yaml.tags.api :refer :all]) ;; For convenience, i.e. (!Sub "...")
nil
user=> (def tpl (cfn/generate-string
  #_=>  {:Parameters {:NamePrefix {:Type "String"}}
  #_=>   :Resources (into {} (for [stage [:dev :test :prod]
  #_=>                             :let [stage-name (name stage)]]
  #_=>                         [stage-name {:Type "AWS::S3::Bucket"
  #_=>                                      :Properties {:BucketName (!Sub (str "${NamePrefix}-" stage-name))}}]))}))
#'user/tpl
user=> (println tpl)
Parameters:
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
nil
user=> (cfn/parse tpl)
#ordered/map ([:Parameters #ordered/map ([:NamePrefix #ordered/map ([:Type "String"])])]
              [:Resources #ordered/map ([:dev #ordered/map ([:Type "AWS::S3::Bucket"]
                                                            [:Properties #ordered/map ([:BucketName #cfn_yaml.tags.!Sub{:string "${NamePrefix}-dev", :bindings{}}])])]
                                        [:test #ordered/map ([:Type "AWS::S3::Bucket"]
                                                             [:Properties #ordered/map ([:BucketName #cfn_yaml.tags.!Sub{:string "${NamePrefix}-test", :bindings {}}])])]
                                        [:prod #ordered/map ([:Type "AWS::S3::Bucket"]
                                                             [:Properties #ordered/map ([:BucketName #cfn_yaml.tags.!Sub{:string "${NamePrefix}-prod", :bindings {}}])])])])
user=> (require '[cognitect.aws.client.api :as aws])
nil
user=> (def cfn-client (aws/client {:api :cloudformation}))
#'user/cfn-client
user=> (aws/invoke cfn-client {:op :CreateStack
  #_=>                         :request {:StackName "cfn-yaml-demo"
  #_=>                                   :TemplateBody tpl
  #_=>                                   :Parameters [{:ParameterKey "NamePrefix"
  #_=>                                                 :ParameterValue (str (java.util.UUID/randomUUID))}]}})
{:StackId "arn:aws:cloudformation:eu-west-1:262355063596:stack/cfn-yaml-demo/290de310-0c25-11e9-9e69-0a611b368f3e"}
user=> (def s (aws/invoke cfn-client {:op :DescribeStackResources
  #_=>                                :request {:StackName "cfn-yaml-demo"}}))
#'user/s
user=> (doseq [{:keys [LogicalResourceId PhysicalResourceId]} (:StackResources s)]
  #_=>   (println LogicalResourceId PhysicalResourceId))
dev 2b5a9713-292c-488a-b4e0-32196a34471b-dev
prod 2b5a9713-292c-488a-b4e0-32196a34471b-prod
test 2b5a9713-292c-488a-b4e0-32196a34471b-test
```

## License

Copyright © 2018-2020 Kimmo Koskinen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
