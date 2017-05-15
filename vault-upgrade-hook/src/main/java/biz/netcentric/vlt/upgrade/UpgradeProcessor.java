/*
 * (C) Copyright 2016 Netcentric AG.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.vlt.upgrade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.vault.packaging.InstallContext;
import org.apache.jackrabbit.vault.packaging.InstallContext.Phase;
import org.apache.jackrabbit.vault.packaging.InstallHook;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageId;

import biz.netcentric.vlt.upgrade.UpgradeInfo.RunMode;
import biz.netcentric.vlt.upgrade.util.PackageInstallLogger;

/**
 * This class is the main entry point for the <b>vault-upgrade-hook</b>
 * execution. {@link #execute(InstallContext)} is called for each content
 * package installation {@link Phase}. On {@link Phase#PREPARE} the environment
 * consisting of {@link UpgradeStatus}, {@link UpgradeInfo}s and
 * {@link UpgradeAction}s is created. The packages are executed for each phase.
 * On {@link Phase#END} the provided {@link Session} will be saved and the
 * status of the upgrade will be stored to {@code /var/upgrade}.
 */
public class UpgradeProcessor implements InstallHook {

    private static final PackageInstallLogger LOG = PackageInstallLogger.create(UpgradeProcessor.class);

    /**
     * Absolute path where information of the {@link UpgradeInfo} execution are
     * stored.
     */
    public static final String STATUS_PATH = "/var/upgrade";

    /**
     * Relative path within a content package where {@link UpgradeInfo}
     * definitions are stored.
     */
    public static final String UPGRADER_PATH_IN_PACKAGE = ".zip/jcr:content/vlt:definition/upgrader";

    // fields are package private for unit tests
    UpgradeStatus status;
    List<UpgradeInfo> infos;

    @Override
    public void execute(InstallContext ctx) throws PackageException {
        LOG.info(ctx, "starting [{}]", ctx.getPhase());

        try {
            switch (ctx.getPhase()) {
            case PREPARE:
                loadStatus(ctx);
                loadInfos(ctx);
                executeActions(ctx);
                break;
            case INSTALLED:
            case PREPARE_FAILED:
            case INSTALL_FAILED:
                executeActions(ctx);
                break;
            case END:
                executeActions(ctx);
                status.update(ctx);
                updateInfoStatus(ctx);
                ctx.getSession().save();
                break;
            }
        } catch (Exception e) {
            LOG.error(ctx, "Error during content upgrade", e);
            throw new PackageException(e);
        } finally {
            LOG.debug(ctx, "finished [{}]", ctx.getPhase());
        }
    }

    protected void updateInfoStatus(InstallContext ctx) throws RepositoryException {
        LOG.debug(ctx, "updating info status [{}]", infos);
        for (UpgradeInfo info : infos) {
            status.update(ctx, info);
        }
    }

    protected void executeActions(InstallContext ctx) throws RepositoryException {
        LOG.debug(ctx, "starting execution [{}]", infos);
        for (UpgradeInfo info : infos) {
            List<UpgradeAction> actionsOfPhase = info.getActions().get(ctx.getPhase());
            LOG.info(ctx, "executing [{}]: [{}]", info, actionsOfPhase);
            for (UpgradeAction action : actionsOfPhase) {
                if (info.getRunMode() == RunMode.ALWAYS || action.isRelevant(ctx, info)) {
                    LOG.debug(ctx, "executing action [{}]", action);
                    action.execute(ctx);
                } else {
                    LOG.debug(ctx, "action not executed because it did not change since last execution [{}]", action);
                }
            }
        }
    }

    protected void loadInfos(final InstallContext ctx) throws RepositoryException {

        String upgradeInfoPath = ctx.getPackage().getId().getInstallationPath() + UPGRADER_PATH_IN_PACKAGE;
        Node upgradeInfoNode = ctx.getSession().getNode(upgradeInfoPath);
        LOG.debug(ctx, "loading packages [{}]: [{}]", upgradeInfoPath, upgradeInfoNode);

        infos = new ArrayList<>();

        if (upgradeInfoNode != null) {

            NodeIterator nodes = upgradeInfoNode.getNodes();
            while (nodes.hasNext()) {
                Node child = nodes.nextNode();
                final UpgradeInfo info = new UpgradeInfo(ctx, status, child);
                LOG.debug(ctx, "info [{}]", info);
                infos.add(info);
            }

            // sort upgrade infos according to their version and priority
            Collections.sort(infos, new Comparator<UpgradeInfo>() {

                @Override
                public int compare(UpgradeInfo info1, UpgradeInfo info2) {
                    try {
                        return info1.getNode().getName().compareTo(info1.getNode().getName());
                    } catch (RepositoryException e) {
                        LOG.warn(ctx, "Could not compare upgrade infos [{}/{}]", info1.getNode(), info2.getNode(), e);
                        return 0;
                    }
                }
            });
        } else {
            LOG.warn(ctx, "Could not load upgrade info [{}]", upgradeInfoPath);
        }
    }

    protected void loadStatus(InstallContext ctx) throws RepositoryException {
        String statusPath = getStatusPath(ctx.getPackage().getId());
        status = new UpgradeStatus(ctx, statusPath);
    }

    protected String getStatusPath(PackageId packageId) {
        return STATUS_PATH + "/" + packageId.getGroup() + "/" + packageId.getName();
    }

}
