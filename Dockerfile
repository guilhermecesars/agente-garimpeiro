FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY src ./src
COPY public ./public

RUN javac -encoding UTF-8 -d bin src/com/garimpo/*.java

EXPOSE 8081
CMD ["java", "-cp", "bin", "com.garimpo.Main"]
