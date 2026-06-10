import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'Core Concepts',
      items: [
        'core-concepts/client-socket',
        'core-concepts/server-socket',
        'core-concepts/tls',
        'core-concepts/tls-support-matrix',
        'core-concepts/socket-options',
      ],
    },
    {
      type: 'category',
      label: 'QUIC',
      items: [
        'quic/intro',
        'quic/connection',
        'quic/stream-mux',
      ],
    },
    {
      type: 'category',
      label: 'HTTP/3 & WebTransport',
      items: [
        'http3/intro',
        'http3/webtransport',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      items: [
        'guides/building-a-protocol',
      ],
    },
    {
      type: 'category',
      label: 'Platforms',
      items: [
        'platforms/jvm',
        'platforms/apple',
        'platforms/linux',
        'platforms/nodejs',
      ],
    },
  ],
};

export default sidebars;
