package io.jenkins.plugins.gitlabbranchsource.servers.helpers;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainSpecification;
import com.cloudbees.plugins.credentials.domains.HostnameSpecification;
import com.cloudbees.plugins.credentials.domains.SchemeSpecification;
import com.google.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.gitlabbranchsource.credentials.PersonalAccessTokenImpl;
import io.jenkins.plugins.gitlabbranchsource.servers.GitLabServer;
import jenkins.model.Jenkins;
import org.gitlab4j.api.Constants;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.utils.AccessTokenUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.firstOrNull;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId;
import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;
import static com.cloudbees.plugins.credentials.domains.URIRequirementBuilder.fromUri;
import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.defaultIfBlank;
import static org.apache.commons.lang.StringUtils.isEmpty;

@Extension
public class GitLabPersonalAccessTokenCreator extends Descriptor<GitLabPersonalAccessTokenCreator> implements
        Describable<GitLabPersonalAccessTokenCreator> {

    private String tokenName;

    private static final Logger LOGGER = LoggerFactory.getLogger(GitLabPersonalAccessTokenCreator.class);

    private static final List<String> GL_PLUGIN_REQUIRED_SCOPE = ImmutableList.of(
            Constants.ApplicationScope.API.toValue(),
            Constants.ApplicationScope.READ_USER.toValue()
    );

    public GitLabPersonalAccessTokenCreator() {
        super(GitLabPersonalAccessTokenCreator.class);
    }

    @Override
    public Descriptor<GitLabPersonalAccessTokenCreator> getDescriptor() {
        return this;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Convert login and password to token";
    }

    @SuppressWarnings("unused")
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String serverUrl, @QueryParameter String credentialsId) {
        if(!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }
        return new StandardUsernameListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM,
                        Jenkins.getInstance(),
                        StandardUsernamePasswordCredentials.class,
                        fromUri(defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL)).build(),
                        CredentialsMatchers.always()
                ).includeMatchingAs(
                        Jenkins.getAuthentication(),
                        Jenkins.getInstance(),
                        StandardUsernamePasswordCredentials.class,
                        fromUri(defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL)).build(),
                        CredentialsMatchers.always()
                );
    }

    @SuppressWarnings("unused")
    @RequirePOST
    public FormValidation doCreateTokenByCredentials(
            @QueryParameter String serverUrl,
            @QueryParameter String credentialsId) {

        Jenkins.getActiveInstance().checkPermission(Jenkins.ADMINISTER);
        if(isEmpty(credentialsId)) {
            return FormValidation.error("Please specify credentials to create token");
        }

        StandardUsernamePasswordCredentials credentials = firstOrNull(lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    Jenkins.getInstance(),
                    ACL.SYSTEM,
                    fromUri(defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL)).build()),
                    withId(credentialsId));

        if(credentials == null) {
            credentials = firstOrNull(lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    Jenkins.getInstance(),
                    Jenkins.getAuthentication(),
                    fromUri(defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL)).build()),
                    withId(credentialsId));
        }

        if(Objects.isNull(credentials)) {
            return FormValidation.error("Can't create GitLab token, credentials are null");
        }

        tokenName = UUID.randomUUID().toString();
        try {
            String token = AccessTokenUtils.createPersonalAccessToken(
                    defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL),
                    credentials.getUsername(),
                    Secret.toString(credentials.getPassword()),
                    tokenName,
                    GL_PLUGIN_REQUIRED_SCOPE
            );
            saveCredentials(serverUrl, token, credentials.getUsername());
            return FormValidation.ok("Created credentials with id %s ", tokenName);
        } catch (GitLabApiException e) {
            return FormValidation.error(e, "Can't create GL token - %s", e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @RequirePOST
    public FormValidation doCreateTokenByPassword(
            @QueryParameter String serverUrl,
            @QueryParameter String login,
            @QueryParameter String password) {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        tokenName = UUID.randomUUID().toString();
        try {
            String token = AccessTokenUtils.createPersonalAccessToken(
                    defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL),
                    login,
                    password,
                    tokenName,
                    GL_PLUGIN_REQUIRED_SCOPE
            );
            saveCredentials(serverUrl, token, login);
            return FormValidation.ok(
                    "Created credentials with id %s ", tokenName
            );
        } catch (GitLabApiException e) {
            return FormValidation.error(e, "Can't create GL token for %s - %s", login, e.getMessage());
        }
    }

    /**
     * Creates {@link org.jenkinsci.plugins.plaincredentials.StringCredentials} with previously created GitLab
     * Personal Access Token.
     * Adds them to domain extracted from server url (will be generated if no any exists before).
     * Domain will have domain requirements consists of scheme and host from serverAPIUrl arg
     *
     * @param serverUrl to add to domain with host and scheme requirement from this url
     * @param token        GitLab Personal Access Token
     * @param username     used to add to description of newly created credentials
     *
     * @see #saveCredentials(String, PersonalAccessTokenImpl)
     */
    private void saveCredentials(@Nullable String serverUrl, String token, String username) {
        String url = defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL);
        String description = String.format("Auto Generated by %s server for %s user", url, username);
        tokenName = UUID.randomUUID().toString();
        PersonalAccessTokenImpl credentials = new PersonalAccessTokenImpl(
                CredentialsScope.GLOBAL,
                tokenName,
                description,
                token
        );
        saveCredentials(url, credentials);
    }

    /**
     * Saves given credentials in jenkins for domain extracted from server api url
     *
     * @param serverUrl to extract (and create if no any) domain
     * @param credentials to save credentials
     */
    private void saveCredentials(String serverUrl, final PersonalAccessTokenImpl credentials) {
        URI serverUri = URI.create(defaultIfBlank(serverUrl, GitLabServer.GITLAB_SERVER_URL));

        List<DomainSpecification> specifications = asList(
                new SchemeSpecification(serverUri.getScheme()),
                new HostnameSpecification(serverUri.getHost(), null)
        );

        final Domain domain = new Domain(serverUri.getHost(), "GitLab domain (autogenerated)", specifications);
        ACL.impersonate(ACL.SYSTEM, () -> { // passes a runnable lambda
            try {
                new SystemCredentialsProvider.StoreImpl().addDomain(domain, credentials);
            } catch (IOException e) {
                LOGGER.error("Can't add credentials for domain", e);
            }
        });
    }
}
