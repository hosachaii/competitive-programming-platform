FROM gcc:13-bookworm
RUN useradd -m -u 10001 sandboxuser
USER sandboxuser
WORKDIR /box
