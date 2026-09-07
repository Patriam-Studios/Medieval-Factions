FROM eclipse-temurin:17-jdk AS ponder-build

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# Ponder 2.0.0 uses Gradle 7.4.2, so build it separately on JDK 17.
WORKDIR /tmp/ponder-build
RUN git clone https://github.com/Dans-Plugins/Ponder.git .
RUN git checkout 2.0.0
RUN chmod +x gradlew
RUN ./gradlew publishToMavenLocal

FROM eclipse-temurin:25-jdk AS plugin-build

COPY --from=ponder-build /root/.m2/repository /root/.m2/repository
WORKDIR /tmp/mf-build
COPY . .
RUN chmod +x gradlew
RUN ./gradlew clean shadowJar

FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git wget \
    && rm -rf /var/lib/apt/lists/*

# Build the Minecraft 26.2 test server on Java 25.
WORKDIR /testmcserver-build
RUN wget -O BuildTools.jar https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar
RUN git config --global --unset core.autocrlf || :
RUN java -jar BuildTools.jar --rev 26.2

# Copy plugin jar from build output
COPY --from=plugin-build /tmp/mf-build/build/libs /testmcserver-build/MedievalFactions/build/libs

# Copy resources and make post-create.sh executable
COPY ./.testcontainer /resources
RUN chmod +x /resources/post-create.sh

# Run server
WORKDIR /testmcserver
EXPOSE 25565
ENTRYPOINT /resources/post-create.sh
