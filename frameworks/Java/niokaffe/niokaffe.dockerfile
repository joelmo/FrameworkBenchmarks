FROM maven:3.6.1-jdk-11-slim as maven
WORKDIR /niokaffe
COPY pom.xml pom.xml
COPY src src
RUN mvn compile -q

EXPOSE 8080

CMD java \
-server \
-XX:+UseNUMA \
-XX:+UseParallelGC \
-XX:+AggressiveOpts \
-cp target/classes Server
