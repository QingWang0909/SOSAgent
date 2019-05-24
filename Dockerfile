# Use an official Ubuntu image as a base image
FROM ubuntu:16.04

ENV SOSAGENT_REPO https://github.com/QingWang0909/SOSAgent.git

RUN apt-get update && apt-get install -y git-core && apt-get -y install maven && apt-get -y install curl && apt-get -y install vim

# To resolve "add-apt-repository : command not found" issue
RUN apt-get -y install software-properties-common

# Install java 8
RUN \
  add-apt-repository -y ppa:webupd8team/java && \
  apt-get update && \
  apt-get install -y openjdk-8-jdk 

# Clone and install
RUN git clone $SOSAGENT_REPO \

# Compile and run
&& cd SOSAgent/ && mvn -DskipTests=true compile && mvn -DskipTests=true package \

# Set SOS Agent as testing mode
&& sed -i 's/false/true/g' src/main/resources/config.properties

ADD sos_agent_start.sh /SOSAgent/
RUN chmod +x /SOSAgent/sos_agent_start.sh

#RUN cd SOSAgent/ && java -jar target/sosagent.jar &
#CMD sh /SOSAgent/run.sh &
#CMD ["java", "-jar", "SOSAgent/target/sosagent.jar"]

ENTRYPOINT ["sh", "/SOSAgent/sos_agent_start.sh"]

EXPOSE 8002
EXPOSE 9998
EXPOSE 9877
EXPOSE 9878
