/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.as.cli.batch.Batch;
import org.jboss.as.cli.batch.BatchManager;
import org.jboss.as.cli.batch.BatchedCommand;
import org.jboss.as.cli.batch.impl.DefaultBatchManager;
import org.jboss.as.cli.batch.impl.DefaultBatchedCommand;
import org.jboss.as.cli.handlers.ConnectHandler;
import org.jboss.as.cli.handlers.CreateJmsCFHandler;
import org.jboss.as.cli.handlers.CreateJmsQueueHandler;
import org.jboss.as.cli.handlers.CreateJmsResourceHandler;
import org.jboss.as.cli.handlers.CreateJmsTopicHandler;
import org.jboss.as.cli.handlers.DeleteJmsCFHandler;
import org.jboss.as.cli.handlers.DeleteJmsQueueHandler;
import org.jboss.as.cli.handlers.DeleteJmsResourceHandler;
import org.jboss.as.cli.handlers.DeleteJmsTopicHandler;
import org.jboss.as.cli.handlers.PrintWorkingNodeHandler;
import org.jboss.as.cli.handlers.DeployHandler;
import org.jboss.as.cli.handlers.HelpHandler;
import org.jboss.as.cli.handlers.HistoryHandler;
import org.jboss.as.cli.handlers.LsHandler;
import org.jboss.as.cli.handlers.OperationRequestHandler;
import org.jboss.as.cli.handlers.PrefixHandler;
import org.jboss.as.cli.handlers.QuitHandler;
import org.jboss.as.cli.handlers.UndeployHandler;
import org.jboss.as.cli.handlers.batch.BatchClearHandler;
import org.jboss.as.cli.handlers.batch.BatchDiscardHandler;
import org.jboss.as.cli.handlers.batch.BatchEditLineHandler;
import org.jboss.as.cli.handlers.batch.BatchHandler;
import org.jboss.as.cli.handlers.batch.BatchHoldbackHandler;
import org.jboss.as.cli.handlers.batch.BatchListHandler;
import org.jboss.as.cli.handlers.batch.BatchMoveLineHandler;
import org.jboss.as.cli.handlers.batch.BatchRemoveLineHandler;
import org.jboss.as.cli.handlers.batch.BatchRunHandler;
import org.jboss.as.cli.impl.DefaultParsedArguments;
import org.jboss.as.cli.operation.OperationCandidatesProvider;
import org.jboss.as.cli.operation.OperationFormatException;
import org.jboss.as.cli.operation.OperationRequestAddress;
import org.jboss.as.cli.operation.OperationRequestParser;
import org.jboss.as.cli.operation.PrefixFormatter;
import org.jboss.as.cli.operation.impl.DefaultOperationCandidatesProvider;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestAddress;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestBuilder;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestParser;
import org.jboss.as.cli.operation.impl.DefaultPrefixFormatter;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class CommandLineMain {

    private static final CommandRegistry cmdRegistry = new CommandRegistry();
    static {
        cmdRegistry.registerHandler(new HelpHandler(), "help", "h");
        cmdRegistry.registerHandler(new QuitHandler(), "quit", "q", "exit");
        cmdRegistry.registerHandler(new ConnectHandler(), "connect");
        cmdRegistry.registerHandler(new PrefixHandler(), "cd", "cn");
        cmdRegistry.registerHandler(new LsHandler(), "ls");
        cmdRegistry.registerHandler(new HistoryHandler(), "history");
        cmdRegistry.registerHandler(new DeployHandler(), "deploy");
        cmdRegistry.registerHandler(new UndeployHandler(), "undeploy");
        cmdRegistry.registerHandler(new PrintWorkingNodeHandler(), "pwd", "pwn");

        cmdRegistry.registerHandler(new CreateJmsQueueHandler(), "create-jms-queue");
        cmdRegistry.registerHandler(new DeleteJmsQueueHandler(), "delete-jms-queue");
        cmdRegistry.registerHandler(new CreateJmsTopicHandler(), "create-jms-topic");
        cmdRegistry.registerHandler(new DeleteJmsTopicHandler(), "delete-jms-topic");
        cmdRegistry.registerHandler(new CreateJmsCFHandler(), "create-jms-cf");
        cmdRegistry.registerHandler(new DeleteJmsCFHandler(), "delete-jms-cf");
        cmdRegistry.registerHandler(new CreateJmsResourceHandler(), false, "create-jms-resource");
        cmdRegistry.registerHandler(new DeleteJmsResourceHandler(), false, "delete-jms-resource");

        cmdRegistry.registerHandler(new BatchHandler(), "batch");
        cmdRegistry.registerHandler(new BatchDiscardHandler(), "discard-batch");
        cmdRegistry.registerHandler(new BatchListHandler(), "list-batch");
        cmdRegistry.registerHandler(new BatchHoldbackHandler(), "holdback-batch");
        cmdRegistry.registerHandler(new BatchRunHandler(), "run-batch");
        cmdRegistry.registerHandler(new BatchClearHandler(), "clear-batch");
        cmdRegistry.registerHandler(new BatchRemoveLineHandler(), "remove-batch-line");
        cmdRegistry.registerHandler(new BatchMoveLineHandler(), "move-batch-line");
        cmdRegistry.registerHandler(new BatchEditLineHandler(), "edit-batch-line");
    }

    public static void main(String[] args) throws Exception {

        String argError = null;
        String[] commands = null;
        File file = null;
        boolean connect = false;
        String defaultControllerHost = null;
        int defaultControllerPort = -1;
        for(String arg : args) {
            if(arg.startsWith("controller=")) {
                String value = arg.substring(11);
                String portStr = null;
                int colonIndex = value.indexOf(':');
                if(colonIndex < 0) {
                    // default port
                    defaultControllerHost = value;
                } else if(colonIndex == 0) {
                    // default host
                    portStr = value.substring(1);
                } else {
                    defaultControllerHost = value.substring(0, colonIndex);
                    portStr = value.substring(colonIndex + 1);
                }

                if(portStr != null) {
                    int port = -1;
                    try {
                        port = Integer.parseInt(portStr);
                        if(port < 0) {
                            argError = "The port must be a valid non-negative integer: '" + args + "'";
                        } else {
                            defaultControllerPort = port;
                        }
                    } catch(NumberFormatException e) {
                        argError = "The port must be a valid non-negative integer: '" + arg + "'";
                    }
                }
            } else if("--connect".equals(arg)) {
                connect = true;
            } else if(arg.startsWith("file=")) {
                if(file != null) {
                    argError = "Duplicate argument 'file'.";
                    break;
                }
                if(commands != null) {
                    argError = "Only one of 'file', 'commands' or 'command' can appear as the argument at a time.";
                    break;
                }

                final String fileName = arg.substring(5);
                if(!fileName.isEmpty()) {
                    file = new File(fileName);
                    if(!file.exists()) {
                        argError = "File " + file.getAbsolutePath() + " doesn't exist.";
                        break;
                    }
                } else {
                    argError = "Argument 'file' is missing value.";
                    break;
                }
            } else if(arg.startsWith("commands=")) {
                if(file != null) {
                    argError = "Only one of 'file', 'commands' or 'command' can appear as the argument at a time.";
                    break;
                }
                if(commands != null) {
                    argError = "Duplicate argument 'command'/'commands'.";
                    break;
                }
                commands = arg.substring(9).split(",+");
            } else if(arg.startsWith("command=")) {
                if(file != null) {
                    argError = "Only one of 'file', 'commands' or 'command' can appear as the argument at a time.";
                    break;
                }
                if(commands != null) {
                    argError = "Duplicate argument 'command'/'commands'.";
                    break;
                }
                commands = new String[]{arg.substring(8)};
            }
        }

        if(argError != null) {
            System.err.println(argError);
            return;
        }

        if(file != null) {
            processFile(file, defaultControllerHost, defaultControllerPort, connect);
            return;
        }

        if(commands != null) {
            processCommands(commands, defaultControllerHost, defaultControllerPort, connect);
            return;
        }

        // Interactive mode

        final jline.ConsoleReader console = initConsoleReader();
        final CommandContextImpl cmdCtx = new CommandContextImpl(console);
        SecurityActions.addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                cmdCtx.disconnectController();
            }
        }));
        console.addCompletor(cmdCtx.cmdCompleter);

        if(defaultControllerHost != null) {
            cmdCtx.defaultControllerHost = defaultControllerHost;
        }
        if(defaultControllerPort != -1) {
            cmdCtx.defaultControllerPort = defaultControllerPort;
        }

        if(connect) {
            cmdCtx.connectController(null, -1);
        } else {
            cmdCtx.printLine("You are disconnected at the moment." +
                " Type 'connect' to connect to the server or" +
                " 'help' for the list of supported commands.");
        }

        try {
            while (!cmdCtx.terminate) {
                String line = console.readLine(cmdCtx.getPrompt()).trim();
                processLine(cmdCtx, line);
            }
        } finally {
            cmdCtx.disconnectController();
        }
    }

    private static void processCommands(String[] commands, String defaultControllerHost, int defaultControllerPort, final boolean connect) {

        final CommandContextImpl cmdCtx = new CommandContextImpl();
        SecurityActions.addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                cmdCtx.disconnectController(!connect);
            }
        }));

        if(defaultControllerHost != null) {
            cmdCtx.defaultControllerHost = defaultControllerHost;
        }
        if(defaultControllerPort != -1) {
            cmdCtx.defaultControllerPort = defaultControllerPort;
        }

        if(connect) {
            cmdCtx.connectController(null, -1, false);
        }

        try {
            for (int i = 0; i < commands.length && !cmdCtx.terminate; ++i) {
                processLine(cmdCtx, commands[i]);
            }
        } finally {
            if (!cmdCtx.terminate) {
                cmdCtx.terminateSession();
            }
            cmdCtx.disconnectController(!connect);
        }
    }

    private static void processFile(File file, String defaultControllerHost, int defaultControllerPort, final boolean connect) {

        final CommandContextImpl cmdCtx = new CommandContextImpl();
        SecurityActions.addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                cmdCtx.disconnectController(!connect);
            }
        }));

        if(defaultControllerHost != null) {
            cmdCtx.defaultControllerHost = defaultControllerHost;
        }
        if(defaultControllerPort != -1) {
            cmdCtx.defaultControllerPort = defaultControllerPort;
        }

        if(connect) {
            cmdCtx.connectController(null, -1, false);
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            while (!cmdCtx.terminate && line != null) {
                processLine(cmdCtx, line.trim());
                line = reader.readLine();
            }
        } catch (Exception e) {
            cmdCtx.printLine("Failed to process file '" + file.getAbsolutePath() + "'");
            e.printStackTrace();
        } finally {
            StreamUtils.safeClose(reader);
            if (!cmdCtx.terminate) {
                cmdCtx.terminateSession();
            }
            cmdCtx.disconnectController(!connect);
        }
    }

    protected static void processLine(final CommandContextImpl cmdCtx, String line) {
        if (line.isEmpty()) {
            return;
        }

        if(isOperation(line)) {
            cmdCtx.setArgs(null, line);
            if(cmdCtx.isBatchMode()) {
                DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder(cmdCtx.getPrefix());
                try {
                    cmdCtx.getOperationRequestParser().parse(line, builder);
                    ModelNode request = builder.buildRequest();
                    StringBuilder op = new StringBuilder();
                    op.append(cmdCtx.getPrefixFormatter().format(builder.getAddress()));
                    op.append(line.substring(line.indexOf(':')));
                    DefaultBatchedCommand batchedCmd = new DefaultBatchedCommand(op.toString(), request);
                    Batch batch = cmdCtx.getBatchManager().getActiveBatch();
                    batch.add(batchedCmd);
                    cmdCtx.printLine("#" + batch.size() + " " + batchedCmd.getCommand());
                } catch (CommandFormatException e) {
                    cmdCtx.printLine(e.getLocalizedMessage());
                }
            } else {
                cmdCtx.operationHandler.handle(cmdCtx);
            }

        } else {
            String cmd = line;
            String cmdArgs = null;
            for (int i = 0; i < cmd.length(); ++i) {
                if (Character.isWhitespace(cmd.charAt(i))) {
                    cmdArgs = cmd.substring(i + 1).trim();
                    cmd = cmd.substring(0, i);
                    break;
                }
            }
            cmdCtx.setArgs(cmd, cmdArgs);

            CommandHandler handler = cmdRegistry.getCommandHandler(cmd.toLowerCase());
            if(handler != null) {
                if(cmdCtx.isBatchMode() && handler.isBatchMode()) {
                    if(!(handler instanceof OperationCommand)) {
                        cmdCtx.printLine("The command is not allowed in a batch.");
                    } else {
                        try {
                            ModelNode request = ((OperationCommand)handler).buildRequest(cmdCtx);
                            BatchedCommand batchedCmd = new DefaultBatchedCommand(line, request);
                            Batch batch = cmdCtx.getBatchManager().getActiveBatch();
                            batch.add(batchedCmd);
                            cmdCtx.printLine("#" + batch.size() + " " + batchedCmd.getCommand());
                        } catch (OperationFormatException e) {
                            cmdCtx.printLine("Failed to add to batch: " + e.getLocalizedMessage());
                        }
                    }
                } else {
                    handler.handle(cmdCtx);
                }
            } else {
                cmdCtx.printLine("Unexpected command '" + line
                        + "'. Type 'help' for the list of supported commands.");
            }
        }
        cmdCtx.setArgs(null, null);
    }

    protected static jline.ConsoleReader initConsoleReader() {

        final String bindingsName;
        final String osName = SecurityActions.getSystemProperty("os.name").toLowerCase();
        if(osName.indexOf("windows") >= 0) {
            bindingsName = "keybindings/jline-windows-bindings.properties";
        } else if(osName.startsWith("mac")) {
            bindingsName = "keybindings/jline-mac-bindings.properties";
        } else {
            bindingsName = "keybindings/jline-default-bindings.properties";
        }

        ClassLoader cl = SecurityActions.getClassLoader(CommandLineMain.class);
        InputStream bindingsIs = cl.getResourceAsStream(bindingsName);
        if(bindingsIs == null) {
            System.err.println("Failed to locate key bindings for OS '" + osName +"': " + bindingsName);
            try {
                return new jline.ConsoleReader();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to initialize console reader", e);
            }
        } else {
            try {
                final InputStream in = new FileInputStream(FileDescriptor.in);
                String encoding = SecurityActions.getSystemProperty("jline.WindowsTerminal.output.encoding");
                if(encoding == null) {
                    encoding = SecurityActions.getSystemProperty("file.encoding");
                }
                final Writer out = new PrintWriter(new OutputStreamWriter(System.out, encoding));
                return new jline.ConsoleReader(in, out, bindingsIs);
            } catch(Exception e) {
                throw new IllegalStateException("Failed to initialize console reader", e);
            } finally {
                StreamUtils.safeClose(bindingsIs);
            }
        }
    }

    private static boolean isOperation(String line) {
        char firstChar = line.charAt(0);
        return firstChar == '.' || firstChar == ':' || firstChar == '/' || line.startsWith("..") || line.startsWith(".type");
    }

    static class CommandContextImpl implements CommandContext {

        private final jline.ConsoleReader console;
        private final CommandHistory history;

        /** whether the session should be terminated*/
        private boolean terminate;

        /** current command */
        private String cmd;
        /** parsed command arguments */
        private DefaultParsedArguments parsedArgs = new DefaultParsedArguments();

        /** domain or standalone mode*/
        private boolean domainMode;
        /** the controller client */
        private ModelControllerClient client;
        /** the default controller host */
        private String defaultControllerHost = "localhost";
        /** the default controller port */
        private int defaultControllerPort = 9999;
        /** the host of the controller */
        private String controllerHost;
        /** the port of the controller */
        private int controllerPort = -1;
        /** various key/value pairs */
        private Map<String, Object> map = new HashMap<String, Object>();
        /** operation request parser */
        private final OperationRequestParser parser = new DefaultOperationRequestParser();
        /** operation request address prefix */
        private final OperationRequestAddress prefix = new DefaultOperationRequestAddress();
        /** the prefix formatter */
        private final PrefixFormatter prefixFormatter = new DefaultPrefixFormatter();
        /** provider of operation request candidates for tab-completion */
        private final OperationCandidatesProvider operationCandidatesProvider;
        /** operation request handler */
        private final OperationRequestHandler operationHandler;
        /** batches */
        private BatchManager batchManager = new DefaultBatchManager();
        /** the default command completer */
        private final CommandCompleter cmdCompleter;

        /**
         * Non-interactive mode
         */
        private CommandContextImpl() {
            this.console = null;
            this.history = null;
            this.operationCandidatesProvider = null;
            this.cmdCompleter = null;
            operationHandler = new OperationRequestHandler();
        }

        /**
         * Interactive mode
         */
        private CommandContextImpl(jline.ConsoleReader console) {
            this.console = console;

            console.setUseHistory(true);
            String userHome = SecurityActions.getSystemProperty("user.home");
            File historyFile = new File(userHome, ".jboss-cli-history");
            try {
                console.getHistory().setHistoryFile(historyFile);
            } catch (IOException e) {
                System.err.println("Failed to setup the history file " + historyFile.getAbsolutePath() + ": " + e.getLocalizedMessage());
            }

            this.history = new HistoryImpl();
            operationCandidatesProvider = new DefaultOperationCandidatesProvider(this);

            operationHandler = new OperationRequestHandler();

            cmdCompleter = new CommandCompleter(cmdRegistry, this);
        }

        @Override
        public String getArgumentsString() {
            return parsedArgs.getArgumentsString();
        }

        @Override
        public void setArgumentsString(String args) {
            parsedArgs.reset(args);
        }

        @Override
        public void terminateSession() {
            terminate = true;
        }

        @Override
        public void printLine(String message) {
            if (console != null) {
                try {
                    console.printString(message);
                    console.printNewline();
                } catch (IOException e) {
                    System.err.println("Failed to print '" + message + "' to the console: " + e.getLocalizedMessage());
                }
            } else { // non-interactive mode
                System.out.println(message);
            }
        }

        @Override
        public void printColumns(Collection<String> col) {
            if (console != null) {
                try {
                    console.printColumns(col);
                } catch (IOException e) {
                    System.err.println("Failed to print columns '" + col + "' to the console: " + e.getLocalizedMessage());
                }
            } else { // non interactive mode
                for(String item : col) {
                    System.out.println(item);
                }
            }
        }

        @Override
        public void set(String key, Object value) {
            map.put(key, value);
        }

        @Override
        public Object get(String key) {
            return map.get(key);
        }

        @Override
        public ModelControllerClient getModelControllerClient() {
            return client;
        }

        @Override
        public OperationRequestParser getOperationRequestParser() {
            return parser;
        }

        @Override
        public OperationRequestAddress getPrefix() {
            return prefix;
        }

        @Override
        public PrefixFormatter getPrefixFormatter() {

            return prefixFormatter;
        }

        @Override
        public OperationCandidatesProvider getOperationCandidatesProvider() {
            return operationCandidatesProvider;
        }

        private void connectController(String host, int port, boolean loggingEnabled) {
            if(host == null) {
                host = defaultControllerHost;
            }

            if(port < 0) {
                port = defaultControllerPort;
            }

            try {
                ModelControllerClient newClient = ModelControllerClient.Factory.create(host, port);
                if(this.client != null) {
                    disconnectController(loggingEnabled);
                }

                List<String> nodeTypes = Util.getNodeTypes(newClient, new DefaultOperationRequestAddress());
                if (!nodeTypes.isEmpty()) {
                    domainMode = nodeTypes.contains("server-group");
                    if(loggingEnabled) {
                        printLine("Connected to "
                            + (domainMode ? "domain controller at " : "standalone controller at ")
                            + host + ":" + port);
                    }
                    client = newClient;
                    this.controllerHost = host;
                    this.controllerPort = port;
                } else {
                    printLine("The controller is not available at " + host + ":" + port);
                }
            } catch (UnknownHostException e) {
                printLine("Failed to resolve host '" + host + "': " + e.getLocalizedMessage());
            }
        }

        @Override
        public void connectController(String host, int port) {
            connectController(host, port, true);
        }

        private void disconnectController(boolean loggingEnabled) {
            if(this.client != null) {
                StreamUtils.safeClose(client);
                if(loggingEnabled) {
                    printLine("Closed connection to " + this.controllerHost + ':' + this.controllerPort);
                }
                client = null;
                this.controllerHost = null;
                this.controllerPort = -1;
                domainMode = false;
            }
            promptConnectPart = null;
        }

        @Override
        public void disconnectController() {
            disconnectController(true);
        }

        @Override
        public String getControllerHost() {
            return controllerHost;
        }

        @Override
        public int getControllerPort() {
            return controllerPort;
        }

        String promptConnectPart;

        String getPrompt() {
            StringBuilder buffer = new StringBuilder();
            if(promptConnectPart == null) {
                buffer.append('[');
                if (controllerHost != null) {
                    if (domainMode) {
                        buffer.append("domain@");
                    } else {
                        buffer.append("standalone@");
                    }
                    buffer.append(controllerHost).append(':').append(controllerPort).append(' ');
                    promptConnectPart = buffer.toString();
                } else {
                    buffer.append("disconnected ");
                }
            } else {
                buffer.append(promptConnectPart);
            }

            if (prefix.isEmpty()) {
                buffer.append('/');
            } else {
                buffer.append(prefix.getNodeType());
                final String nodeName = prefix.getNodeName();
                if (nodeName != null) {
                    buffer.append('=').append(nodeName);
                }
            }

            if (isBatchMode()) {
                buffer.append(" #");
            }
            buffer.append("] ");
            return buffer.toString();
        }

        @Override
        public CommandHistory getHistory() {
            return history;
        }

        @Override
        public String getDefaultControllerHost() {
            return defaultControllerHost;
        }

        @Override
        public int getDefaultControllerPort() {
            return defaultControllerPort;
        }

        private void setArgs(String cmd, String args) {
            this.cmd = cmd;
            setArgumentsString(args);
        }

        @Override
        public boolean isBatchMode() {
            return batchManager.isBatchActive();
        }

        @Override
        public String getCommand() {
            return cmd;
        }

        @Override
        public BatchManager getBatchManager() {
            return batchManager;
        }

        @Override
        public BatchedCommand toBatchedCommand(String line) throws OperationFormatException {

            if (line.isEmpty()) {
                throw new IllegalArgumentException("Null command line.");
            }

            final String originalCommand = this.cmd;
            final String originalArguments = this.parsedArgs.getArgumentsString();
            if(isOperation(line)) {
                try {
                    setArgs(null, line);
                    DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder(getPrefix());
                    parser.parse(line, builder);
                    ModelNode request = builder.buildRequest();
                    StringBuilder op = new StringBuilder();
                    op.append(prefixFormatter.format(builder.getAddress()));
                    op.append(line.substring(line.indexOf(':')));
                    return new DefaultBatchedCommand(op.toString(), request);
                } finally {
                    setArgs(originalCommand, originalArguments);
                }
            }

            String cmd = line;
            String cmdArgs = null;
            for (int i = 0; i < cmd.length(); ++i) {
                if (Character.isWhitespace(cmd.charAt(i))) {
                    cmdArgs = cmd.substring(i + 1).trim();
                    cmd = cmd.substring(0, i);
                    break;
                }
            }

            CommandHandler handler = cmdRegistry.getCommandHandler(cmd.toLowerCase());
            if(handler == null) {
                throw new OperationFormatException("No command handler for '" + cmd + "'.");
            }
            if(!(handler instanceof OperationCommand)) {
                throw new OperationFormatException("The command is not allowed in a batch.");
            }

            try {
                setArgs(cmd, cmdArgs);
                ModelNode request = ((OperationCommand)handler).buildRequest(this);
                return new DefaultBatchedCommand(line, request);
            } finally {
                setArgs(originalCommand, originalArguments);
            }
        }

        @Override
        public CommandLineCompleter getDefaultCommandCompleter() {
            return cmdCompleter;
        }

        @Override
        public ParsedArguments getParsedArguments() {
            return parsedArgs;
        }

        @Override
        public boolean isDomainMode() {
            return domainMode;
        }

        private class HistoryImpl implements CommandHistory {

            @SuppressWarnings("unchecked")
            @Override
            public List<String> asList() {
                return console.getHistory().getHistoryList();
            }

            @Override
            public boolean isUseHistory() {
                return console.getUseHistory();
            }

            @Override
            public void setUseHistory(boolean useHistory) {
                console.setUseHistory(useHistory);
            }

            @Override
            public void clear() {
                console.getHistory().clear();
            }
        }
    }
}
