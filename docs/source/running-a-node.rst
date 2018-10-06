Running nodes locally
=====================

.. contents::

.. note:: You should already have generated your node(s) with their CorDapps installed by following the instructions in
   :doc:`generating-a-node`.

There are several ways to run a Corda node locally for testing purposes.

Starting a Corda node using DemoBench
-------------------------------------
See the instructions in :doc:`demobench`.

.. _starting-an-individual-corda-node:

Starting a Corda node from the command line
-------------------------------------------
Run a node by opening a terminal window in the node's folder and running:

.. code-block:: shell

   java -jar corda.jar

By default, the node will look for a configuration file called ``node.conf`` and a CorDapps folder called ``cordapps``
in the current working directory. You can override the configuration file and workspace paths on the command line (e.g.
``./corda.jar --config-file=test.conf --base-directory=/opt/corda/nodes/test``).

You can increase the amount of Java heap memory available to the node using the ``-Xmx`` command line argument. For
example, the following would run the node with a heap size of 2048MB:

.. code-block:: shell

   java -Xmx2048m -jar corda.jar

You should do this if you receive an ``OutOfMemoryError`` exception when interacting with the node.

Optionally run the node's webserver as well by opening a terminal window in the node's folder and running:

.. code-block:: shell

   java -jar corda-webserver.jar

.. warning:: The node webserver is for testing purposes only and will be removed soon.

Command-line options
~~~~~~~~~~~~~~~~~~~~
The node can optionally be started with the following command-line options:

* ``--base-directory``, ``-b``: The node working directory where all the files are kept (default: ``.``).
* ``--bootstrap-raft-cluster``: Bootstraps Raft cluster. The node forms a single node cluster (ignoring otherwise configured peer 
  addresses), acting as a seed for other nodes to join the cluster.
* ``--clear-network-map-cache``, ``-c``: Clears local copy of network map, on node startup it will be restored from server or file system.
* ``--config-file``, ``-f``: The path to the config file. Defaults to ``node.conf``.
* ``--dev-mode``, ``-d``: Runs the node in developer mode. Unsafe in production. Defaults to true on MacOS and desktop versions of Windows. False otherwise.
* ``--initial-registration``: Start initial node registration with the compatibility zone to obtain a certificate from the Doorman.
* ``--just-generate-node-info``: Perform the node start-up task necessary to generate its nodeInfo, save it to disk, then
  quit.
* ``--just-generate-rpc-ssl-settings``: Generate the ssl keystore and truststore for a secure RPC connection.
* ``--network-root-truststore``, ``-t``: Network root trust store obtained from network operator.
* ``--network-root-truststore-password``, ``-p``: Network root trust store password obtained from network operator.
* ``--no-local-shell``, ``-n``: Do not start the embedded shell locally.
* ``--on-unknown-config-keys <[FAIL,WARN,INFO]>``: How to behave on unknown node configuration. Defaults to FAIL.
* ``--sshd``: Enables SSH server for node administration.
* ``--sshd-port``: Sets the port for the SSH server. If not supplied and SSH server is enabled, the port defaults to 2222.
* ``--verbose``, ``--log-to-console``, ``-v``: If set, prints logging to the console as well as to a file.
* ``--logging-level=<loggingLevel>``: Enable logging at this level and higher. Possible values: ERROR, WARN, INFO, DEBUG, TRACE. Default: INFO.
* ``--install-shell-extensions``: Install ``corda`` alias and auto completion for bash and zsh. See :doc:`cli-application-shell-extensions` for more info.
* ``--help``, ``-h``: Show this help message and exit.
* ``--version``, ``-V``: Print version information and exit.

.. _enabling-remote-debugging:

Enabling remote debugging
~~~~~~~~~~~~~~~~~~~~~~~~~
To enable remote debugging of the node, run the node with the following JVM arguments:

``java -Dcapsule.jvm.args="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005" -jar corda.jar``

This will allow you to attach a debugger to your node on port 5005.

Starting all nodes at once on a local machine from the command line
-------------------------------------------------------------------

.. _starting-all-nodes-at-once:

Native
~~~~~~
If you created your nodes using ``deployNodes``, a ``runnodes`` shell script (or batch file on Windows) will have been
generated to allow you to quickly start up all nodes and their webservers. ``runnodes`` should only be used for testing
purposes.

Start the nodes with ``runnodes`` by running the following command from the root of the project:

* Linux/macOS: ``build/nodes/runnodes``
* Windows: ``call build\nodes\runnodes.bat``

.. warning:: On macOS, do not click/change focus until all the node terminal windows have opened, or some processes may
   fail to start.

If you receive an ``OutOfMemoryError`` exception when interacting with the nodes, you need to increase the amount of
Java heap memory available to them, which you can do when running them individually. See
:ref:`starting-an-individual-corda-node`.

docker-compose
~~~~~~~~~~~~~~
If you created your nodes using ``Dockerform``, the ``docker-compose.yml`` file and corresponding ``Dockerfile`` for
nodes has been created and configured appropriately. Navigate to ``build/nodes`` directory and run ``docker-compose up``
command. This will startup nodes inside new, internal network.
After the nodes are started up, you can use ``docker ps`` command to see how the ports are mapped.

.. warning:: You need both ``Docker`` and ``docker-compose`` installed and enabled to use this method. Docker CE
   (Community Edition) is enough. Please refer to `Docker CE documentation <https://www.docker.com/community-edition>`_
   and `Docker Compose documentation <https://docs.docker.com/compose/install/>`_ for installation instructions for all
   major operating systems.

Starting all nodes at once on a remote machine from the command line
--------------------------------------------------------------------

By default, ``Cordform`` expects the nodes it generates to be run on the same machine where they were generated.
In order to run the nodes remotely, the nodes can be deployed locally and then copied to a remote server.
If after copying the nodes to the remote machine you encounter errors related to ``localhost`` resolution, you will additionally need to follow the steps below.

To create nodes locally and run on a remote machine perform the following steps:

1. Configure Cordform task and deploy the nodes locally as described in :doc:`generating-a-node`.

2. Copy the generated directory structure to a remote machine using e.g. Secure Copy.

3. Optionally, bootstrap the network on the remote machine.

   This is optional step when a remote machine doesn't accept ``localhost`` addresses, or the generated nodes are configured to run on another host's IP address.

   If required change host addresses in top level configuration files ``[NODE NAME]_node.conf`` for entries ``p2pAddress`` , ``rpcSettings.address`` and  ``rpcSettings.adminAddress``.

   Run the network bootstrapper tool to regenerate the nodes network map (see for more explanation :doc:`network-bootstrapper`):

   ``java -jar corda-tools-network-bootstrapper-Master.jar --dir <nodes-root-dir>``

4. Run nodes on the remote machine using :ref:`runnodes command <starting-all-nodes-at-once>`.

The above steps create a test deployment as ``deployNodes`` Gradle task would do on a local machine.
