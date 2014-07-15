/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
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

package org.jboss.as.patching.cli;

import static java.lang.System.getProperty;
import static java.lang.System.getSecurityManager;
import static java.lang.System.getenv;
import static java.security.AccessController.doPrivileged;

import java.io.File;
import java.security.PrivilegedAction;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.handlers.CommandHandlerWithHelp;
import org.jboss.as.cli.handlers.DefaultFilenameTabCompleter;
import org.jboss.as.cli.handlers.FilenameTabCompleter;
import org.jboss.as.cli.handlers.SimpleTabCompleter;
import org.jboss.as.cli.handlers.WindowsFilenameTabCompleter;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.ArgumentWithoutValue;
import org.jboss.as.cli.impl.DefaultCompleter;
import org.jboss.as.cli.impl.FileSystemPathArgument;
import org.jboss.as.cli.operation.ParsedCommandLine;
import org.jboss.as.cli.util.SimpleTable;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.patching.Constants;
import org.jboss.as.patching.PatchMessages;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.metadata.Identity;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.PatchElement;
import org.jboss.as.patching.metadata.PatchXml;
import org.jboss.as.patching.tool.PatchOperationBuilder;
import org.jboss.as.patching.tool.PatchOperationTarget;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2012 Red Hat Inc.
 */
public class PatchHandler extends CommandHandlerWithHelp {

    static final String PATCH = "patch";
    static final String APPLY = "apply";
    static final String ROLLBACK = "rollback";
    static final String HISTORY = "history";
    static final String INFO = "info";
    static final String INSPECT = "inspect";

    private final ArgumentWithValue host;

    private final ArgumentWithValue action;

    private final ArgumentWithoutValue path;

    private final ArgumentWithValue patchId;
    private final ArgumentWithoutValue rollbackTo;
    private final ArgumentWithValue resetConfiguration;

    private final ArgumentWithoutValue overrideModules;
    private final ArgumentWithoutValue overrideAll;
    private final ArgumentWithValue override;
    private final ArgumentWithValue preserve;

    private final ArgumentWithoutValue distribution;
    private final ArgumentWithoutValue modulePath;
    private final ArgumentWithoutValue bundlePath;

    private static final String lineSeparator = getSecurityManager() == null ? getProperty("line.separator") : doPrivileged(new PrivilegedAction<String>() {
                @Override
                public String run() {
                    return getProperty("line.separator");
                }
            });

    private final ArgumentWithoutValue verbose;

    public PatchHandler(final CommandContext context) {
        super(PATCH, false);

        action = new ArgumentWithValue(this, new SimpleTabCompleter(new String[]{APPLY, ROLLBACK, HISTORY, INFO, INSPECT}), 0, "--action");

        host = new ArgumentWithValue(this, new DefaultCompleter(CandidatesProviders.HOSTS), "--host") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                boolean connected = ctx.getControllerHost() != null;
                return connected && ctx.isDomainMode() && super.canAppearNext(ctx);
            }
        };

        // apply & rollback arguments

        overrideModules = new ArgumentWithoutValue(this, "--override-modules") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, APPLY, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        overrideModules.addRequiredPreceding(action);

        overrideAll = new ArgumentWithoutValue(this, "--override-all") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, APPLY, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        overrideAll.addRequiredPreceding(action);

        override = new ArgumentWithValue(this, "--override") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, APPLY, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        override.addRequiredPreceding(action);

        preserve = new ArgumentWithValue(this, "--preserve") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, APPLY, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        preserve.addRequiredPreceding(action);

        // apply arguments

        final FilenameTabCompleter pathCompleter = Util.isWindows() ? new WindowsFilenameTabCompleter(context) : new DefaultFilenameTabCompleter(context);
        path = new FileSystemPathArgument(this, pathCompleter, 1, "--path") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, APPLY, INSPECT)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }

        };
        path.addRequiredPreceding(action);

        // rollback arguments

        patchId = new ArgumentWithValue(this, 1, "--patch-id") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, INFO, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        patchId.addRequiredPreceding(action);

        rollbackTo = new ArgumentWithoutValue(this, "--rollback-to") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        rollbackTo.addRequiredPreceding(action);

        resetConfiguration = new ArgumentWithValue(this, SimpleTabCompleter.BOOLEAN, "--reset-configuration") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, ROLLBACK)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        resetConfiguration.addRequiredPreceding(action);

        distribution = new FileSystemPathArgument(this, pathCompleter, "--distribution") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                // TODO this is hidden from the tab-completion for now (and also not documented),
                // although if the argument name is typed in and followed with the '=',
                // the tab-completion for its value will work
                return false;
            }
        };

        modulePath = new FileSystemPathArgument(this, pathCompleter, "--module-path") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                // TODO this is hidden from the tab-completion for now (and also not documented),
                // although if the argument name is typed in and followed with the '=',
                // the tab-completion for its value will work
                return false;
            }
        };

        bundlePath = new FileSystemPathArgument(this, pathCompleter, "--bundle-path") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                // TODO this is hidden from the tab-completion for now (and also not documented),
                // although if the argument name is typed in and followed with the '=',
                // the tab-completion for its value will work
                return false;
            }
        };

        verbose = new ArgumentWithoutValue(this, "--verbose", "-v") {
            @Override
            public boolean canAppearNext(CommandContext ctx) throws CommandFormatException {
                if (canOnlyAppearAfterActions(ctx, INFO, INSPECT)) {
                    return super.canAppearNext(ctx);
                }
                return false;
            }
        };
        verbose.addRequiredPreceding(action);
        verbose.addRequiredPreceding(patchId);
    }

    private boolean canOnlyAppearAfterActions(CommandContext ctx, String... actions) {
        final String actionStr = this.action.getValue(ctx.getParsedCommandLine());
        if(actionStr == null || actions.length == 0) {
            return false;
        }
        return Arrays.asList(actions).contains(actionStr);
    }

    @Override
    protected void doHandle(CommandContext ctx) throws CommandLineException {
        final ParsedCommandLine parsedLine = ctx.getParsedCommandLine();
        final String action = this.action.getValue(parsedLine);
        if(INSPECT.equals(action)) {
            doInspect(ctx);
            return;
        }
        final PatchOperationTarget target = createPatchOperationTarget(ctx);
        final PatchOperationBuilder builder = createPatchOperationBuilder(parsedLine);
        final ModelNode response;
        try {
            response = builder.execute(target);
        } catch (Exception e) {
            throw new CommandLineException(action + " failed", e);
        }
        if (!Util.isSuccess(response)) {
            final ModelNode fd = response.get(ModelDescriptionConstants.FAILURE_DESCRIPTION);
            if(!fd.isDefined()) {
                throw new CommandLineException("Failed to apply patch: " + response.asString());
            }
            if(fd.has(Constants.CONFLICTS)) {
                final StringBuilder buf = new StringBuilder();
                buf.append(fd.get(Constants.MESSAGE).asString()).append(": ");
                final ModelNode conflicts = fd.get(Constants.CONFLICTS);
                String title = "";
                if(conflicts.has(Constants.BUNDLES)) {
                    formatConflictsList(buf, conflicts, "", Constants.BUNDLES);
                    title = ", ";
                }
                if(conflicts.has(Constants.MODULES)) {
                    formatConflictsList(buf, conflicts, title, Constants.MODULES);
                    title = ", ";
                }
                if(conflicts.has(Constants.MISC)) {
                    formatConflictsList(buf, conflicts, title, Constants.MISC);
                }
                buf.append(lineSeparator).append("Use the --override-all, --override=[] or --preserve=[] arguments in order to resolve the conflict.");
                throw new CommandLineException(buf.toString());
            } else {
                throw new CommandLineException(Util.getFailureDescription(response));
            }
        }

        if(INFO.equals(action)) {
            if(patchId.getValue(parsedLine) != null) {
                final ModelNode result = response.get(ModelDescriptionConstants.RESULT);
                if(!result.isDefined()) {
                    return;
                }
                SimpleTable table = new SimpleTable(2);
                table.addLine(new String[]{"Patch ID:", result.get(Constants.PATCH_ID).asString()});
                table.addLine(new String[]{"Type:", result.get(Constants.TYPE).asString()});
                table.addLine(new String[]{"Identity name:", result.get(Constants.IDENTITY_NAME).asString()});
                table.addLine(new String[]{"Identity version:", result.get(Constants.IDENTITY_VERSION).asString()});
                table.addLine(new String[]{"Description:", result.get(Constants.DESCRIPTION).asString()});
                ctx.printLine(table.toString(false));

                final ModelNode elements = result.get(Constants.ELEMENTS);
                if(elements.isDefined()) {
                    ctx.printLine("");
                    ctx.printLine("ELEMENTS");
                    for(ModelNode e : elements.asList()) {
                        table = new SimpleTable(2);
                        table.addLine(new String[]{"Patch ID:", e.get(Constants.PATCH_ID).asString()});
                        table.addLine(new String[]{"Name:", e.get(Constants.NAME).asString()});
                        table.addLine(new String[]{"Type:", e.get(Constants.TYPE).asString()});
                        table.addLine(new String[]{"Description:", e.get(Constants.DESCRIPTION).asString()});
                        ctx.printLine("");
                        ctx.printLine(table.toString(false));
                    }
                }
            } else {
                ctx.printLine(response.toJSONString(false));
            }
        } else {
            ctx.printLine(response.toJSONString(false));
        }
    }

    protected void doInspect(CommandContext ctx) throws CommandLineException {
        final ParsedCommandLine parsedLine = ctx.getParsedCommandLine();
        final String patchPath = path.getValue(parsedLine, true);
        final File patchFile = new File(patchPath);
        if(!patchFile.exists()) {
            throw new CommandLineException("Failed to locate " + patchFile.getAbsolutePath());
        }
        ZipFile patchZip = null;
        InputStream is = null;
        Patch patch;
        try {
            patchZip = new ZipFile(patchFile);
            final ZipEntry patchXmlEntry = patchZip.getEntry("patch.xml");
            if(patchXmlEntry == null) {
                throw new CommandLineException("Failed to locate patch.xml inside " + patchFile.getAbsolutePath());
            }
            is = patchZip.getInputStream(patchXmlEntry);
            patch = PatchXml.parse(is).resolvePatch(null, null);
        } catch (ZipException e) {
            throw new CommandLineException("Failed to open " + patchFile.getAbsolutePath(), e);
        } catch (IOException e) {
            throw new CommandLineException("Failed to open " + patchFile.getAbsolutePath(), e);
        } catch (PatchingException e) {
            throw new CommandLineException("Failed to resolve parsed patch", e);
        } catch (XMLStreamException e) {
            throw new CommandLineException("Failed to parse patch.xml", e);
        } finally {
            if(is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
            if (patchZip != null) {
                try {
                    patchZip.close();
                } catch (IOException e) {
                }
            }
        }

        final Identity identity = patch.getIdentity();
        SimpleTable table = new SimpleTable(2);
        table.addLine(new String[]{"Patch ID:", patch.getPatchId()});
        table.addLine(new String[]{"Type:", identity.getPatchType().getName()});
        table.addLine(new String[]{"Identity name:", identity.getName()});
        table.addLine(new String[]{"Identity version:", identity.getVersion()});
        table.addLine(new String[]{"Description:", patch.getDescription() == null ? "n/a" : patch.getDescription()});
        ctx.printLine(table.toString(false));

        if(verbose.isPresent(parsedLine)) {
            ctx.printLine("");
            ctx.printLine("ELEMENTS");
            for(PatchElement e : patch.getElements()) {
                table = new SimpleTable(2);
                table.addLine(new String[]{"Patch ID:", e.getId()});
                table.addLine(new String[]{"Name:", e.getProvider().getName()});
                table.addLine(new String[]{"Type:", e.getProvider().isAddOn() ? Constants.ADD_ON : Constants.LAYER});
                table.addLine(new String[]{"Description:", e.getDescription()});
                ctx.printLine("");
                ctx.printLine(table.toString(false));
            }
        }
    }

    protected void formatConflictsList(final StringBuilder buf, final ModelNode conflicts, String title, String contentType) {
        buf.append(title);
        final List<ModelNode> list = conflicts.get(contentType).asList();
        int i = 0;
        while(i < list.size()) {
            final ModelNode item = list.get(i++);
            buf.append(item.asString());
            if(i < list.size()) {
                buf.append(", ");
            }
        }
    }

    private PatchOperationBuilder createPatchOperationBuilder(ParsedCommandLine args) throws CommandFormatException {
        final String action = this.action.getValue(args, true);

        PatchOperationBuilder builder;
        if (APPLY.equals(action)) {
            final String path = this.path.getValue(args, true);

            final File f = new File(path);
            if(!f.exists()) {
                // i18n is never used for CLI exceptions
                throw new CommandFormatException("Path " + f.getAbsolutePath() + " doesn't exist.");
            }
            if(f.isDirectory()) {
                throw new CommandFormatException(f.getAbsolutePath() + " is a directory.");
            }
            builder = PatchOperationBuilder.Factory.patch(f);
        } else if (ROLLBACK.equals(action)) {
            String resetConfigValue = resetConfiguration.getValue(args, true);
            boolean resetConfig;
            if(Util.TRUE.equalsIgnoreCase(resetConfigValue)) {
                resetConfig = true;
            } else if(Util.FALSE.equalsIgnoreCase(resetConfigValue)) {
                resetConfig = false;
            } else {
                throw new CommandFormatException("Unexpected value for --reset-configuration (only true and false are allowed): " + resetConfigValue);
            }
            if(patchId.isPresent(args)) {
                final String id = patchId.getValue(args, true);
                final boolean rollbackTo = this.rollbackTo.isPresent(args);
                builder = PatchOperationBuilder.Factory.rollback(id, rollbackTo, resetConfig);
            } else {
                builder = PatchOperationBuilder.Factory.rollbackLast(resetConfig);
            }
        } else if (INFO.equals(action)) {
            final String pId = patchId.getValue(args);
            if(pId == null) {
                builder = PatchOperationBuilder.Factory.info();
            } else {
                builder = PatchOperationBuilder.Factory.info(pId, verbose.isPresent(args));
            }
            return builder;
        } else if (HISTORY.equals(action)) {
            builder = PatchOperationBuilder.Factory.history();
            return builder;
        } else {
            throw new CommandFormatException("Unrecognized action '" + action + "'");
        }

        if (overrideModules.isPresent(args)) {
            builder.ignoreModuleChanges();
        }
        if (overrideAll.isPresent(args)) {
            builder.overrideAll();
        }
        if (override.isPresent(args)) {
            final String overrideList = override.getValue(args);
            if(overrideList == null || overrideList.isEmpty()) {
                throw new CommandFormatException(override.getFullName() + " is missing value.");
            }
            for (String path : overrideList.split(",+")) {
                builder.overrideItem(path);
            }
        }
        if (preserve.isPresent(args)) {
            final String preserveList = preserve.getValue(args);
            if(preserveList == null || preserveList.isEmpty()) {
                throw new CommandFormatException(preserve.getFullName() + " is missing value.");
            }
            for (String path : preserveList.split(",+")) {
                builder.preserveItem(path);
            }
        }
        return builder;
    }

    private PatchOperationTarget createPatchOperationTarget(CommandContext ctx) throws CommandLineException {
        final PatchOperationTarget target;
        boolean connected = ctx.getControllerHost() != null;
        if (connected) {
            if (ctx.isDomainMode()) {
                String hostName = host.getValue(ctx.getParsedCommandLine(), true);
                target = PatchOperationTarget.createHost(hostName, ctx.getModelControllerClient());
            } else {
                target = PatchOperationTarget.createStandalone(ctx.getModelControllerClient());
            }
        } else {
            final ParsedCommandLine args = ctx.getParsedCommandLine();
            final String jbossHome = getJBossHome(args);
            final File root = new File(jbossHome);
            final List<File> modules = getFSArgument(modulePath, args, root, "modules");
            final List<File> bundles = getFSArgument(bundlePath, args, root, "bundles");
            try {
                target = PatchOperationTarget.createLocal(root, modules, bundles);
            } catch (Exception e) {
                throw new CommandLineException("Unable to apply patch to local JBOSS_HOME=" + jbossHome, e);
            }
        }
        return target;
    }

    private static final String HOME = "JBOSS_HOME";
    private static final String HOME_DIR = "jboss.home.dir";

    private String getJBossHome(final ParsedCommandLine args) {
        final String targetDistro = distribution.getValue(args);
        if(targetDistro != null) {
            return targetDistro;
        }

        String resolved = getSecurityManager() == null ? getenv(HOME) : doPrivileged(new PrivilegedAction<String>() {
            @Override
            public String run() {
                return getenv(HOME);
            }
        });
        if (resolved == null) {
            resolved = getSecurityManager() == null ? getProperty(HOME_DIR) : doPrivileged(new PrivilegedAction<String>() {
                @Override
                public String run() {
                    return getProperty(HOME_DIR);
                }
            });
        }
        if (resolved == null) {
            throw PatchMessages.MESSAGES.cliFailedToResolveDistribution();
        }
        return resolved;
    }

    private static List<File> getFSArgument(final ArgumentWithoutValue arg, final ParsedCommandLine args, final File root, final String param) {
        final String value = arg.getValue(args);
        if (value != null) {
            final String[] values = value.split(Pattern.quote(File.pathSeparator));
            if (values.length == 1) {
                return Collections.singletonList(new File(value));
            }
            final List<File> resolved = new ArrayList<File>(values.length);
            for (final String path : values) {
                resolved.add(new File(path));
            }
            return resolved;
        }
        return Collections.singletonList(new File(root, param));
    }

}
