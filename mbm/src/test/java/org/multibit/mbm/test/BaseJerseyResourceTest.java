package org.multibit.mbm.test;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.LowLevelAppDescriptor;
import com.yammer.dropwizard.bundles.JavaBundle;
import com.yammer.dropwizard.jersey.DropwizardResourceConfig;
import com.yammer.dropwizard.jersey.JacksonMessageBodyProvider;
import com.yammer.dropwizard.jersey.OptionalQueryParamInjectableProvider;
import com.yammer.dropwizard.json.Json;
import org.codehaus.jackson.map.Module;
import org.junit.After;
import org.junit.Before;
import org.multibit.mbm.auth.hmac.HmacAuthenticator;
import org.multibit.mbm.auth.hmac.HmacClientFilter;
import org.multibit.mbm.auth.hmac.HmacRestrictedToProvider;
import org.multibit.mbm.db.DatabaseLoader;
import org.multibit.mbm.db.dao.UserDao;
import org.multibit.mbm.db.dto.*;

import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A base test class for testing Dropwizard resources against
 * an actual Jersey container
 */
public abstract class BaseJerseyResourceTest extends BaseResourceTest {
  private final Set<Object> singletons = Sets.newHashSet();
  private final Set<Class<?>> providers = Sets.newHashSet();
  private final List<Module> modules = Lists.newArrayList();
  private final Map<String, Boolean> features = Maps.newHashMap();

  private JerseyTest test;
  protected UriInfo uriInfo;

  // Authentication support using HMAC
  private Optional<String> apiKey = Optional.of("trent123");
  private Optional<String> sharedSecret = Optional.of("trent456");

  /**
   * <p>Subclasses must use this to configure mocks of any objects that the
   * test object depends on.</p>
   *
   * @throws Exception If something goes wrong
   */
  protected abstract void setUpResources() throws Exception;

  protected void addSingleton(Object singleton) {
    singletons.add(singleton);
  }

  public void addProvider(Class<?> klass) {
    providers.add(klass);
  }

  protected void addJacksonModule(Module module) {
    modules.add(module);
  }

  protected void addFeature(String feature, Boolean value) {
    features.put(feature, value);
  }

  protected Json getJson() {
    final Json json = new Json();
    for (Module module : modules) {
      json.registerModule(module);
    }
    return json;
  }

  protected Client client() {
    return test.client();
  }

  @Before
  public void setUpJersey() throws Exception {
    setUpResources();
    this.test = new JerseyTest() {
      @Override
      protected AppDescriptor configure() {
        final DropwizardResourceConfig config = new DropwizardResourceConfig(true);
        // Default singletons
        for (Object provider : JavaBundle.DEFAULT_PROVIDERS) {
          config.getSingletons().add(provider);
        }
        // Configure Jackson
        final Json json = getJson();
        config.getSingletons().add(new JacksonMessageBodyProvider(json));
        config.getSingletons().addAll(singletons);

        // Add providers
        for (Class<?> provider : providers) {
          config.getClasses().add(provider);
        }
        config.getClasses().add(OptionalQueryParamInjectableProvider.class);

        // Add any features (see FeaturesAndProperties)
        for (Map.Entry<String, Boolean> feature : features.entrySet()) {
          config.getFeatures().put(feature.getKey(), feature.getValue());
        }

        return new LowLevelAppDescriptor.Builder(config).build();
      }
    };

    // Allow final client request filtering for HMAC authentication (this allows for secure tests)
    test.client().addFilter(new HmacClientFilter(
      apiKey.get(),
      sharedSecret.get(),
      test.client().getProviders()
    )
    );

    test.setUp();
  }

  @After
  public void tearDownJersey() throws Exception {
    if (test != null) {
      test.tearDown();
    }
  }

  /**
   * Provides the default authentication settings
   */
  protected User setUpAuthenticator() {

    Role customerRole = DatabaseLoader.buildCustomerRole();

    return setUpAuthenticator(apiKey, sharedSecret, Lists.newArrayList(customerRole));
  }

  /**
   * Provides the default authentication settings
   */
  protected User setUpAuthenticator(List<Role> roles) {
    return setUpAuthenticator(apiKey, sharedSecret, roles);
  }

  /**
   * @param apiKey       The API key to assign to the User (default is "abc123")
   * @param sharedSecret The shared secret to assign (default is "def456")
   */
  protected User setUpAuthenticator(Optional<String> apiKey, Optional<String> sharedSecret, List<Role> roles) {

    Customer customer = CustomerBuilder
      .newInstance()
      .build();

    User user = UserBuilder
      .newInstance()
      .withUUID(apiKey.get())
      .withSecretKey(sharedSecret.get())
      .withRoles(roles)
      .withCustomer(customer)
      .build();

    UserDao userDao = mock(UserDao.class);
    when(userDao.getByUUID(apiKey.get())).thenReturn(Optional.of(user));

    HmacAuthenticator authenticator = new HmacAuthenticator();
    authenticator.setUserDao(userDao);

    addSingleton(new HmacRestrictedToProvider<User>(authenticator, "REST"));

    return user;
  }

}