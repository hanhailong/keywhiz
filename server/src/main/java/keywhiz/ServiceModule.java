/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package keywhiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.dropwizard.Configuration;
import io.dropwizard.auth.basic.BasicCredentials;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.java8.auth.Authenticator;
import io.dropwizard.setup.Environment;
import java.sql.SQLException;
import java.time.Clock;
import keywhiz.auth.BouncyCastle;
import keywhiz.auth.User;
import keywhiz.auth.cookie.CookieConfig;
import keywhiz.auth.cookie.CookieModule;
import keywhiz.auth.cookie.SessionCookie;
import keywhiz.auth.xsrf.Xsrf;
import keywhiz.generators.SecretGeneratorBindingModule;
import keywhiz.generators.TemplatedSecretGenerator;
import keywhiz.service.config.Readonly;
import keywhiz.service.crypto.ContentCryptographer;
import keywhiz.service.crypto.CryptoModule;
import keywhiz.service.crypto.SecretTransformer;
import keywhiz.service.daos.AclDAO;
import keywhiz.service.daos.ClientDAO;
import keywhiz.service.daos.GroupDAO;
import keywhiz.service.daos.SecretContentDAO;
import keywhiz.service.daos.SecretController;
import keywhiz.service.daos.SecretDAO;
import keywhiz.service.daos.SecretSeriesDAO;
import keywhiz.utility.DSLContexts;
import org.jooq.DSLContext;

import static com.google.common.base.Preconditions.checkNotNull;
import static keywhiz.JooqHealthCheck.OnFailure.LOG_ONLY;
import static keywhiz.JooqHealthCheck.OnFailure.RETURN_UNHEALTHY;

public class ServiceModule extends AbstractModule {
  private final Environment environment;
  private final KeywhizConfig config;

  public ServiceModule(KeywhizConfig config, Environment environment) {
    this.config = checkNotNull(config);
    this.environment = checkNotNull(environment);
  }

  @Override protected void configure() {
    // Initialize the BouncyCastle security provider for cryptography support.
    BouncyCastle.require();

    bind(Clock.class).toInstance(Clock.systemUTC());

    install(new CookieModule(config.getCookieKey()));
    install(new CryptoModule(config.getDerivationProviderClass(), config.getContentKeyStore()));

    bind(CookieConfig.class).annotatedWith(SessionCookie.class)
        .toInstance(config.getSessionCookieConfig());
    bind(CookieConfig.class).annotatedWith(Xsrf.class)
        .toInstance(config.getXsrfCookieConfig());

    // TODO(justin): Consider https://github.com/HubSpot/dropwizard-guice.
    bind(Environment.class).toInstance(environment);
    bind(Configuration.class).toInstance(config);
    bind(KeywhizConfig.class).toInstance(config);

    install(new SecretGeneratorBindingModule() {
      @Override protected void configure() {
        bindSecretGenerator("templated", TemplatedSecretGenerator.class);
      }
    });
  }

  // ManagedDataSource

  @Provides @Singleton ManagedDataSource dataSource(Environment environment,
      KeywhizConfig config) {
    DataSourceFactory dataSourceFactory = config.getDataSourceFactory();
    ManagedDataSource dataSource = dataSourceFactory.build(environment.metrics(), "db-writable");
    environment.lifecycle().manage(dataSource);

    environment.healthChecks().register("db-read-write-health",
        new JooqHealthCheck(dataSource, LOG_ONLY));

    return dataSource;
  }

  @Provides @Singleton @Readonly ManagedDataSource readonlyDataSource(Environment environment,
      KeywhizConfig config) {
    DataSourceFactory dataSourceFactory = config.getReadonlyDataSourceFactory();
    ManagedDataSource dataSource = dataSourceFactory.build(environment.metrics(), "db-readonly");
    environment.lifecycle().manage(dataSource);

    environment.healthChecks().register("db-readonly-health",
        new JooqHealthCheck(dataSource, RETURN_UNHEALTHY));

    return dataSource;
  }

  @Provides ObjectMapper configuredObjectMapper(Environment environment) {
    return environment.getObjectMapper();
  }

  // jOOQ

  @Provides @Singleton DSLContext jooqContext(ManagedDataSource dataSource) throws SQLException {
    return DSLContexts.databaseAgnostic(dataSource);
  }

  @Provides @Singleton
  @Readonly DSLContext readonlyJooqContext(@Readonly ManagedDataSource dataSource)
      throws SQLException {
    return DSLContexts.databaseAgnostic(dataSource);
  }

  // DAOs

  @Provides @Singleton AclDAO aclDAO(DSLContext jooqContext, ClientDAO clientDAO,
      GroupDAO groupDAO, SecretContentDAO secretContentDAO,
      SecretSeriesDAO secretSeriesDAO, ObjectMapper mapper) {
    return new AclDAO(jooqContext, clientDAO, groupDAO, secretContentDAO, secretSeriesDAO, mapper);
  }

  @Provides @Singleton
  @Readonly AclDAO readonlyAclDAO(@Readonly DSLContext jooqContext, @Readonly ClientDAO clientDAO,
      @Readonly GroupDAO groupDAO, @Readonly SecretContentDAO secretContentDAO,
      @Readonly SecretSeriesDAO secretSeriesDAO, ObjectMapper mapper) {
    return new AclDAO(jooqContext, clientDAO, groupDAO, secretContentDAO, secretSeriesDAO, mapper);
  }

  @Provides @Singleton ClientDAO clientDAO(DSLContext jooqContext) {
    return new ClientDAO(jooqContext);
  }

  @Provides @Singleton @Readonly ClientDAO readonlyClientDAO(@Readonly DSLContext jooqContext) {
    return new ClientDAO(jooqContext);
  }

  @Provides @Singleton GroupDAO groupDAO(DSLContext jooqContext) {
    return new GroupDAO(jooqContext);
  }

  @Provides @Singleton @Readonly GroupDAO readonlyGroupDAO(@Readonly DSLContext jooqContext) {
    return new GroupDAO(jooqContext);
  }

  @Provides @Singleton SecretContentDAO secretContentDAO(DSLContext jooqContext,
      ObjectMapper mapper) {
    return new SecretContentDAO(jooqContext, mapper);
  }

  @Provides @Singleton
  @Readonly SecretContentDAO readonlySecretContentDAO(@Readonly DSLContext jooqContext,
      ObjectMapper mapper) {
    return new SecretContentDAO(jooqContext, mapper);
  }

  @Provides @Singleton SecretSeriesDAO secretSeriesDAO(DSLContext jooqContext,
      ObjectMapper mapper) {
    return new SecretSeriesDAO(jooqContext, mapper);
  }

  @Provides @Singleton
  @Readonly SecretSeriesDAO readonlySecretSeriesDAO(@Readonly DSLContext jooqContext,
      ObjectMapper mapper) {
    return new SecretSeriesDAO(jooqContext, mapper);
  }

  @Provides @Singleton SecretDAO secretDAO(DSLContext jooqContext,
      SecretContentDAO secretContentDAO, SecretSeriesDAO secretSeriesDAO) {
    return new SecretDAO(jooqContext, secretContentDAO, secretSeriesDAO);
  }

  @Provides @Singleton
  @Readonly SecretDAO readonlySecretDAO(@Readonly DSLContext jooqContext,
      @Readonly SecretContentDAO secretContentDAO,
      @Readonly SecretSeriesDAO secretSeriesDAO) {
    return new SecretDAO(jooqContext, secretContentDAO, secretSeriesDAO);
  }

  @Provides @Singleton SecretController secretController(SecretTransformer transformer,
      ContentCryptographer cryptographer, SecretDAO secretDAO) {
    return new SecretController(transformer, cryptographer, secretDAO);
  }

  @Provides @Singleton
  @Readonly SecretController readonlySecretController(SecretTransformer transformer,
      ContentCryptographer cryptographer, @Readonly SecretDAO secretDAO) {
    return new SecretController(transformer, cryptographer, secretDAO);
  }

  @Provides @Singleton
  @Readonly Authenticator<BasicCredentials, User> authenticator(KeywhizConfig config,
      @Readonly DSLContext jooqContext) {
    return config.getUserAuthenticatorFactory().build(jooqContext);
  }
}
