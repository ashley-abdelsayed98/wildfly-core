/*
 * Copyright 2018 JBoss by Red Hat.
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
package org.jboss.as.server.controller.git;


import static org.eclipse.jgit.lib.Constants.DEFAULT_REMOTE_NAME;
import static org.eclipse.jgit.lib.Constants.DOT_GIT_IGNORE;
import static org.eclipse.jgit.lib.Constants.DOT_GIT;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.MASTER;
import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.sshd.IdentityPasswordProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.jboss.as.server.logging.ServerLogger;
import org.wildfly.client.config.ConfigXMLParseException;

/**
 * Abstraction over a git repository.
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class GitRepository implements Closeable {

    private final Set<String> ignored;
    private final Repository repository;
    private final Path basePath;
    private final String defaultRemoteRepository;
    private final String branch;
    private final boolean sign;

    public GitRepository(GitRepositoryConfiguration gitConfig)
            throws IllegalArgumentException, IOException, ConfigXMLParseException, GeneralSecurityException {
        this.basePath = gitConfig.getBasePath();
        this.branch = gitConfig.getBranch();
        this.ignored = gitConfig.getIgnored();
        this.defaultRemoteRepository = gitConfig.getRepository();
        this.sign = gitConfig.isSign();
        File baseDir = basePath.toFile();
        File gitDir = new File(baseDir, DOT_GIT);
        if (gitConfig.getAuthenticationConfig() != null) {
            CredentialsProvider.setDefault(new ElytronClientCredentialsProvider(gitConfig.getAuthenticationConfig()));
        }

        SshSessionFactory sshdSessionFactory = new SshdSessionFactory() {
            @Override
            protected KeyPasswordProvider createKeyPasswordProvider(CredentialsProvider provider){
                return new IdentityPasswordProvider(provider) {
                    @Override
                    public char[] getPassphrase(URIish var1, int var2) throws IOException{
                        return "your_passphrase".toCharArray(); //replace string with your passphrase
                    }
                };
            }
        };
        SshSessionFactory.setInstance(sshdSessionFactory);
        if (gitDir.exists()) {
            try {
                repository = new FileRepositoryBuilder().setWorkTree(baseDir).setGitDir(gitDir).setup().build();
            } catch (IOException ex) {
                throw ServerLogger.ROOT_LOGGER.failedToPullRepository(ex, gitConfig.getRepository());
            }
            try (Git git = Git.wrap(repository)) {
                git.clean();
                if (!isLocalGitRepository(gitConfig.getRepository())) {
                    String remote = getRemoteName(gitConfig.getRepository());
                    checkoutToSelectedBranch(git);
                    PullResult result = git.pull().setRemote(remote).setRemoteBranchName(branch).setStrategy(MergeStrategy.RESOLVE).call();
                    if (!result.isSuccessful()) {
                        throw ServerLogger.ROOT_LOGGER.failedToPullRepository(null, gitConfig.getRepository());
                    }
                } else {
                    if (!this.branch.equals(repository.getBranch())) {
                        CheckoutCommand checkout = git.checkout().setName(branch);
                        checkout.call();
                        if (checkout.getResult().getStatus() == CheckoutResult.Status.ERROR) {
                            throw ServerLogger.ROOT_LOGGER.failedToPullRepository(null, gitConfig.getRepository());
                        }
                    }
                }
            } catch (GitAPIException ex) {
                throw ServerLogger.ROOT_LOGGER.failedToPullRepository(ex, gitConfig.getRepository());
            }
        } else {
            if (isLocalGitRepository(gitConfig.getRepository())) {
                try (Git git = Git.init().setDirectory(baseDir).call()) {
                    final AddCommand addCommand = git.add();
                    addCommand.addFilepattern("data/content/");
                    Path configurationDir = basePath.resolve("configuration");
                    try (Stream<Path> files = Files.list(configurationDir)) {
                        files.filter(configFile -> !"logging.properties".equals(configFile.getFileName().toString()) && Files.isRegularFile(configFile))
                                .forEach(configFile -> addCommand.addFilepattern(getPattern(configFile)));
                    }
                    addCommand.call();
                    createGitIgnore(git, basePath);
                    git.commit().setSign(sign).setMessage(ServerLogger.ROOT_LOGGER.repositoryInitialized()).call();
                } catch (GitAPIException | IOException ex) {
                    throw ServerLogger.ROOT_LOGGER.failedToInitRepository(ex, gitConfig.getRepository());
                }
            } else {
                clearExistingFiles(basePath, gitConfig.getRepository());
                try (Git git = Git.init().setDirectory(baseDir).call()) {
                    String remoteName = UUID.randomUUID().toString();
                    StoredConfig config = git.getRepository().getConfig();
                    config.setString("remote", remoteName, "url", gitConfig.getRepository());
                    config.setString("remote", remoteName, "fetch", "+" + R_HEADS + "*:" + R_REMOTES + remoteName + "/*");
                    config.save();
                    git.clean();
                    git.pull().setRemote(remoteName).setRemoteBranchName(branch).setStrategy(MergeStrategy.RESOLVE).call();
                    checkoutToSelectedBranch(git);
                    if (createGitIgnore(git, basePath)) {
                        git.commit().setSign(sign).setMessage(ServerLogger.ROOT_LOGGER.addingIgnored()).call();
                    }
                } catch (GitAPIException ex) {
                    throw ServerLogger.ROOT_LOGGER.failedToInitRepository(ex, gitConfig.getRepository());
                }
            }
            repository = new FileRepositoryBuilder().setWorkTree(baseDir).setGitDir(gitDir).setup().build();
        }
        ServerLogger.ROOT_LOGGER.usingGit();
    }

    private void checkoutToSelectedBranch(final Git git) throws IOException, GitAPIException {
        boolean createBranch = !ObjectId.isId(branch);
        if (createBranch) {
            Ref ref = git.getRepository().exactRef(R_HEADS + branch);
            if (ref != null) {
                createBranch = false;
            }
        }
        CheckoutCommand checkout = git.checkout().setCreateBranch(createBranch).setName(branch);
        checkout.call();
        if (checkout.getResult().getStatus() == CheckoutResult.Status.ERROR) {
            throw ServerLogger.ROOT_LOGGER.failedToPullRepository(null, defaultRemoteRepository);
        }
    }

    public GitRepository(Repository repository) {
        this.repository = repository;
        this.ignored = Collections.emptySet();
        this.defaultRemoteRepository = DEFAULT_REMOTE_NAME;
        this.branch = MASTER;
        this.sign = false;
        if (repository.isBare()) {
            this.basePath = repository.getDirectory().toPath();
        } else {
            this.basePath = repository.getDirectory().toPath().getParent();
        }
        ServerLogger.ROOT_LOGGER.usingGit();
    }

    private void clearExistingFiles(Path root, String gitRepository) {
        try {
            Files.walkFileTree(root, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!ignored.contains(dir.getFileName().toString() + '/')) {
                    return FileVisitResult.CONTINUE;
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        ServerLogger.ROOT_LOGGER.deletingFile(file);
                        Files.delete(file);
                    } catch (IOException ioex) {
                        ServerLogger.ROOT_LOGGER.debug(ioex.getMessage(), ioex);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    try {
                        ServerLogger.ROOT_LOGGER.deletingFile(dir);
                        Files.delete(dir);
                    } catch (IOException ioex) {
                        ServerLogger.ROOT_LOGGER.debug(ioex.getMessage(), ioex);
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException ex) {
            throw ServerLogger.ROOT_LOGGER.failedToInitRepository(ex, gitRepository);
        }
    }

    private boolean createGitIgnore(Git git, Path root) throws IOException, GitAPIException {
        Path gitIgnore = root.resolve(DOT_GIT_IGNORE);
        if (Files.notExists(gitIgnore)) {
            Files.write(gitIgnore, ignored);
            git.add().addFilepattern(DOT_GIT_IGNORE).call();
            return true;
        }
        return false;
    }

    private boolean isLocalGitRepository(String gitRepository) {
        return "local".equals(gitRepository);
    }

    public Git getGit() {
        return Git.wrap(repository);
    }

    public File getDirectory() {
        return repository.getDirectory();
    }

    public boolean isBare() {
        return repository.isBare();
    }

    @Override
    public void close() {
        this.repository.close();
    }

    public String getPattern(File file) {
        return getPattern(file.toPath());
    }

    public String getPattern(Path file) {
        return basePath.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    public String getBranch() {
        return branch;
    }

    public boolean isSign() {
        return sign;
    }

    public final boolean isValidRemoteName(String remoteName) {
        return repository.getRemoteNames().contains(remoteName);
    }

    public final String getRemoteName(String gitRepository) {
        return findRemoteName(gitRepository == null || gitRepository.isEmpty() ? defaultRemoteRepository : gitRepository);
    }

    private String findRemoteName(String gitRepository) {
        if (isValidRemoteName(gitRepository)) {
            return gitRepository;
        }
        StoredConfig config = repository.getConfig();
        for (String remoteName : repository.getRemoteNames()) {
            if (gitRepository.equals(config.getString("remote", remoteName, "url"))) {
                return remoteName;
            }
        }
        return null;
    }

    /**
     * Reset hard on HEAD.
     *
     * @throws GitAPIException
     */
    public void rollback() throws GitAPIException {
        try (Git git = getGit()) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(HEAD).call();
        }
    }

    /**
     * Commit all changes if there are uncommitted changes.
     *
     * @param msg the commit message.
     * @throws GitAPIException
     */
    public void commit(String msg) throws GitAPIException {
        try (Git git = getGit()) {
            Status status = git.status().call();
            if (!status.isClean()) {
                git.commit().setSign(sign).setMessage(msg).setAll(true).setNoVerify(true).call();
            }
        }
    }
}
