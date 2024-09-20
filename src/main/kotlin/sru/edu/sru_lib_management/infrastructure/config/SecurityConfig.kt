/*
 * Copyright (c) 2024.
 * @Author Phel Viwath
 */

package sru.edu.sru_lib_management.infrastructure.config

import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.AuthenticationWebFilter
import reactor.core.publisher.Mono
import sru.edu.sru_lib_management.auth.data.repository.AuthRepositoryImp
import sru.edu.sru_lib_management.auth.domain.jwt.JwtAuthenticationConverter
import sru.edu.sru_lib_management.auth.domain.jwt.JwtAuthenticationManager
import java.util.*

@Suppress("DEPRECATION")
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class SecurityConfig (
    private val repository: AuthRepositoryImp,
    @Value("\${mail-sender.password}") val mailSenderPassword: String,
    @Value("\${mail-sender.username}") val mailSenderUsername: String
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailServices(
        encoder: PasswordEncoder
    ): ReactiveUserDetailsService = ReactiveUserDetailsService { email ->
        mono {
            val users = repository.findByEmail(email)
            users?.let {
                User.withUsername(it.email)
                    .password(it.password)
                    .roles(it.roles.name)
                    .build()
            }
        }
    }
    @Bean
    @Suppress("removal", "DEPRECATION")
    fun springSecurityFilterChain(
        http: ServerHttpSecurity,
        converter: JwtAuthenticationConverter,
        authManager: JwtAuthenticationManager
    ): SecurityWebFilterChain {
        val filter = AuthenticationWebFilter(authManager)
        filter.setServerAuthenticationConverter(converter)
        http
            .exceptionHandling()
            .authenticationEntryPoint { exchange, _ ->
                Mono.fromRunnable {
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                }
            }
            .and()
            .authorizeExchange()
            .pathMatchers(
                "api/v1/auth/**",
                "/api/v1/**",
                "api/v1/entry/check",
            ).permitAll()
            .anyExchange().authenticated()
            .and()
            .addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
            .httpBasic().disable()
            .formLogin().disable()
            .cors().disable()
            .csrf().disable()
        return http.build()
    }

    @Bean
    fun javaMailSender(): JavaMailSender {
        val mailSender = JavaMailSenderImpl()
        mailSender.host = "smtp.gmail.com"
        mailSender.port = 587
        mailSender.username = mailSenderUsername
        mailSender.password = mailSenderPassword

        val props: Properties = mailSender.javaMailProperties
        props["mail.transport.protocol"] = "smtp"
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.starttls.enable"] = "true"
        props["mail.debug"] = "true"

        return mailSender
    }

}
