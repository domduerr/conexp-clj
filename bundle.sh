#!/bin/bash

CONEXP_CLJ_JAR=conexp-clj-$(cat VERSION)-$(date +%Y%m%d.%H%M%S).jar
export LEIN_SNAPSHOTS_IN_RELEASE=1

lein clean && lein deps                                       && \
rm -rfv conexp-clj/ conexp-clj.zip                            && \
lein jar $CONEXP_CLJ_JAR                                      && \
mkdir -p conexp-clj/lib/                                      && \
cp stuff/libs/*.jar conexp-clj/lib/                           && \
cp stuff/libs/*.clj conexp-clj/lib                            && \
cp -r bin res AUTHORS LICENSE README conexp-clj/              && \
cp lib/*.jar conexp-clj/lib/                                  && \
mv $CONEXP_CLJ_JAR conexp-clj/lib/                            && \
zip -r conexp-clj.zip conexp-clj
