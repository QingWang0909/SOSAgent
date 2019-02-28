# Use an official Ubuntu image as a base image
FROM ubuntu:16.04

ENV SOSAGENT_REPO https://github.com/QingWang0909/SOSAgent.git

RUN apt-get update && apt-get install -y git-core && apt-get -y install maven && apt-get -y install curl

# To resolve "add-apt-repository : command not found" issue
RUN apt-get -y install software-properties-common

# Install java 8
RUN \
  echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
  add-apt-repository -y ppa:webupd8team/java && \
  apt-get update && \
  apt-get install -y oracle-java8-installer --allow-unauthenticated

# Clone and install
RUN git clone $SOSAGENT_REPO \

# Compile and run
&& cd SOSAgent/ && mvn -DskipTests=true compile && mvn -DskipTests=true package


ADD run.sh /SOSAgent/
RUN chmod +x /SOSAgent/run.sh

#RUN cd SOSAgent/ && java -jar target/sosagent.jar &

#CMD sh /SOSAgent/run.sh &

# TODO : make it work, maybe by change and upload to github
RUN sed -i 's/info/debug/g' /SOSAgent/src/main/resources/logback.xml

CMD ["java", "-jar", "SOSAgent/target/sosagent.jar"]

EXPOSE 8002 