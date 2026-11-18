# ClusterFuzzLite build image for the fuzz tests (#178). The base image provides JDK 17 and the Jazzer driver.
# Pinned by digest; Dependabot does not update it, so refresh the digest from time to time.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:bd13a763de1a0fb22099442094e7e0049657a8c47f01e9d7af8bb849e0ad4699

# The build requires Maven 3.9+ (maven-enforcer-plugin); the distribution package is older. Checksum-verified.
ARG MAVEN_VERSION=3.9.14
ARG MAVEN_SHA512=d50af8ab5e6005b46a07f0ce9d3719e67cfdf898da988a84871304cd59fb1af0fef2f99dea709e6e66f21f732f905979b5c2dce6b6860406f60a70e84d9cf0b8
RUN curl -fsSL -o /tmp/maven.tar.gz \
        "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz
ENV MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}
ENV PATH=$MAVEN_HOME/bin:$PATH

COPY . $SRC/facepalm-maven-plugin
WORKDIR $SRC/facepalm-maven-plugin
COPY .clusterfuzzlite/build.sh $SRC/
