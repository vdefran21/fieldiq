package com.fieldiq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [RedisRepositoriesAutoConfiguration::class])
class FieldIQApplication

fun main(args: Array<String>) {
    runApplication<FieldIQApplication>(*args)
}
