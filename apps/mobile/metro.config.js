const { getDefaultConfig } = require('expo/metro-config');
const path = require('node:path');

/**
 * Monorepo Metro config.
 *
 * npm workspaces hoists dependencies to the repo root, and @th/types lives
 * outside apps/mobile. Metro needs to be told about both or it will fail to
 * resolve them at bundle time even though TypeScript is happy.
 */
const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
];
config.resolver.disableHierarchicalLookup = true;

module.exports = config;
