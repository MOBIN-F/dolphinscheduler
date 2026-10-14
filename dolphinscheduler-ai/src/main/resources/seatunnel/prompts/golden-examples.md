<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

  Verified with the SeaTunnel engine validation, keep them valid when editing.
-->
### MySQL to S3 parquet (BATCH)
```hocon
env {
  parallelism = 2
  job.mode = "BATCH"
}

source {
  Jdbc {
    url = "jdbc:mysql://mysql-host:3306/shop"
    driver = "com.mysql.cj.jdbc.Driver"
    username = "${mysql_user}"
    password = "${mysql_password}"
    query = "SELECT id, user_id, amount, created_at FROM orders"
    plugin_output = "orders"
  }
}

sink {
  S3File {
    plugin_input = "orders"
    bucket = "s3a://my-bucket"
    path = "/warehouse/orders"
    tmp_path = "/tmp/seatunnel"
    fs.s3a.endpoint = "s3.us-east-1.amazonaws.com"
    fs.s3a.aws.credentials.provider = "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
    access_key = "${aws_access_key}"
    secret_key = "${aws_secret_key}"
    file_format_type = "parquet"
  }
}
```

### Kafka to Clickhouse (STREAMING)
```hocon
env {
  parallelism = 2
  job.mode = "STREAMING"
  checkpoint.interval = 10000
}

source {
  Kafka {
    bootstrap.servers = "kafka:9092"
    topic = "events"
    consumer.group = "seatunnel_events"
    format = "json"
    start_mode = "latest"
    plugin_output = "events"
    schema {
      fields {
        id = "bigint"
        event_type = "string"
        event_time = "timestamp"
      }
    }
  }
}

sink {
  Clickhouse {
    plugin_input = "events"
    host = "clickhouse:8123"
    database = "default"
    table = "events"
    username = "${clickhouse_user}"
    password = "${clickhouse_password}"
  }
}
```

### MySQL to PostgreSQL with a Sql transform (routing with plugin_output / plugin_input)
```hocon
env {
  parallelism = 1
  job.mode = "BATCH"
}

source {
  Jdbc {
    url = "jdbc:mysql://mysql-host:3306/shop"
    driver = "com.mysql.cj.jdbc.Driver"
    username = "${mysql_user}"
    password = "${mysql_password}"
    query = "SELECT id, name, age FROM users"
    plugin_output = "users"
  }
}

transform {
  Sql {
    plugin_input = "users"
    plugin_output = "adult_users"
    query = "SELECT id, name, age FROM users WHERE age >= 18"
  }
}

sink {
  Jdbc {
    plugin_input = "adult_users"
    url = "jdbc:postgresql://pg-host:5432/dw"
    driver = "org.postgresql.Driver"
    username = "${pg_user}"
    password = "${pg_password}"
    generate_sink_sql = true
    database = "dw"
    table = "public.adult_users"
  }
}
```
