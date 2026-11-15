# Build a static binary, then run it on a distroless base as a non-root user.
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /upstream .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /upstream /upstream
EXPOSE 8080
ENTRYPOINT ["/upstream"]
