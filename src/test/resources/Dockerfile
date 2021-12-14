FROM prom/prometheus
EXPOSE 9090
COPY metrics .
RUN /bin/promtool tsdb create-blocks-from openmetrics metrics /prometheus