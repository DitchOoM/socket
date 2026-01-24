import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'Socket',
  tagline: 'Kotlin Multiplatform suspend-based socket networking',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  // Ensure static files are served correctly
  staticDirectories: ['static'],

  url: 'https://ditchoom.github.io',
  baseUrl: '/socket/',

  organizationName: 'DitchOoM',
  projectName: 'socket',
  trailingSlash: false,

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  // Kotlin Playground for interactive examples
  scripts: [
    {
      src: 'https://unpkg.com/kotlin-playground@1',
      async: true,
    },
  ],

  themes: ['docusaurus-theme-github-codeblock'],

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/DitchOoM/socket/tree/main/docs/',
          routeBasePath: '/', // Docs at root
        },
        blog: false, // Disable blog
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    // GitHub codeblock configuration
    codeblock: {
      showGithubLink: true,
      githubLinkLabel: 'View on GitHub',
    },
    navbar: {
      title: 'Socket',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          href: 'pathname:///api/index.html',
          label: 'API Reference',
          position: 'left',
        },
        {
          href: 'https://github.com/DitchOoM/socket',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentation',
          items: [
            {
              label: 'Getting Started',
              to: '/getting-started',
            },
            {
              label: 'Core Concepts',
              to: '/core-concepts/client-socket',
            },
          ],
        },
        {
          title: 'Resources',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/DitchOoM/socket',
            },
            {
              label: 'Maven Central',
              href: 'https://central.sonatype.com/artifact/com.ditchoom/socket',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'DitchOoM',
              href: 'https://github.com/DitchOoM',
            },
            {
              label: 'Buffer Library',
              href: 'https://ditchoom.github.io/buffer/',
            },
          ],
        },
      ],
      copyright: `Copyright \u00a9 ${new Date().getFullYear()} DitchOoM. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'groovy', 'java', 'bash'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
