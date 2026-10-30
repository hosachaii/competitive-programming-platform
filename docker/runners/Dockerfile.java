FROM eclipse-temurin:17-jdk-jammy
RUN useradd -m -u 10001 sandboxuser
USER sandboxuser
WORKDIR /box
