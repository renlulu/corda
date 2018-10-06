# CorDapp Minimum and Target Platform Version

## Overview

We want to give CorDapps the ability to specify which versions of the platform they support. This will make it easier for CorDapp developers to support multiple platform versions, and enable CorDapp developers to tweak behaviour and opt in to changes that might be breaking (e.g. sandboxing). Corda developers gain the ability to introduce changes to the implementation of the API that would otherwise break existing CorDapps.

This document proposes that CorDapps will have metadata associated with them specifying a minimum platform version and a target platform Version. The minimum platform version of a CorDapp would indicate that a Corda node would have to be running at least this version of the Corda platform in order to be able to run this CorDapp. The target platform version of a CorDapp would indicate that it was tested for this version of the Corda platform.

## Background

> Introduce target version and min platform version as app attributes
>
> This is probably as simple as a couple of keys in a MANIFEST.MF file.
> We should document what it means, make sure API implementations can always access the target version of the calling CorDapp (i.e. by examining the flow, doing a stack walk or using Reflection.getCallerClass()) and do a simple test of an API that acts differently depending on the target version of the app.
> We should also implement checking at CorDapp load time that min platform version <= current platform version.

([from CORDA-470](https://r3-cev.atlassian.net/browse/CORDA-470))

### Definitions

* *Platform version (Corda)* An integer representing the API version of the Corda platform 

> It starts at 1 and will increment by exactly 1 for each release which changes any of the publicly exposed APIs in the entire platform. This includes public APIs on the node itself, the RPC system, messaging, serialisation, etc. API backwards compatibility will always be maintained, with the use of deprecation to migrate away from old APIs. In rare situations APIs may have to be removed, for example due to security issues. There is no relationship between the Platform Version and the release version - a change in the major, minor or patch values may or may not increase the Platform Version.
  
([from the docs](https://docs.corda.net/head/versioning.html#versioning)). 

* *Platform version (Node)* The value of the Corda platform version that a node is running and advertising to the network.

* *Minimum platform version (Network)* The minimum platform version that the nodes must run in order to be able to join the network. Set by the network zone operator. The minimum platform version is distributed with the network parameters as `minimumPlatformVersion`.
 ([see docs:](https://docs.corda.net/network-map.html#network-parameters))

* *Target platform version (CorDapp)* Introduced in this document. Indicates that a CorDapp was tested with this version of the Corda Platform and should be run at this API level if possible.

* *Minimum platform version (CorDapp)* Introduced in this document. Indicates the minimum version of the Corda platform that a Corda Node has to run in order to be able to run a CorDapp.


## Goals

Define the semantics of target platform version and minimum platform version attributes for CorDapps, and the minimum platform version for the Corda network. Describe how target and platform versions would be specified by CorDapp developers. Define how these values can be accessed by the node and the CorDapp itself.

## Non-goals

In the future it might make sense to integrate the minimum and target versions into a Corda gradle plugin. Such a plugin is out of scope of this document.

## Timeline

This is intended as a long-term solution. The first iteration of the implementation will be part of platform version 4 and contain the minimum and target platform version.

## Requirements
  
* The CorDapp's minimum and target platform version must be accessible to nodes at CorDapp load time.

* At CorDapp load time there should be a check that the node's platform version is greater or equal to the CorDapp's Minimum Platform version.

* API implementations must be able to access the target version of the calling CorDapp.

* The node's platform version must be accessible to CorDapps.

* The CorDapp's target platform version must be accessible to the node when running CorDapps.
      
## Design

### Testing 

When a new platform version is released, CorDapp developers can increase their CorDapp's target version and re-test their app. If the tests are successful, they can then release their CorDapp with the increased target version. This way they would opt-in to potentially breaking changes that were introduced in that version. If they choose to keep their current target version, their CorDapp will continue to work.
    
### Implications for platform developers

When new features or changes are introduced that require all nodes on the network to understand them (e.g. changes in the wire transaction format), they must be version-gated on the network level. This means that the new behaviour should only take effect if the minimum platform version of the network is equal to or greater than the version in which these changes were introduced. Failing that, the old behaviour must be used instead.

Changes that risk breaking apps must be gated on targetVersion>=X where X is the version where the change was made, and the old behaviour must be preserved if that condition isn't met.

## Technical Design

The minimum- and target platform version will be written to the manifest of the CorDapp's JAR, in fields called `Min-Platform-Version` and `Target-Platform-Version`.
The node's CorDapp loader reads these values from the manifest when loading the CorDapp. If the CorDapp's minimum platform version is greater than the node's platform version, the node will not load the CorDapp and log a warning. The CorDapp loader sets the minimum and target version in `net.corda.core.cordapp.Cordapp`, which can be obtained via the `CorDappContext` from the service hub. 

To make APIs caller-sensitive in cases where the service hub is not available a different approach has to be used. It would possible to do a stack walk, and parse the manifest of each class on the stack to determine if it belongs to a CorDapp, and if yes, what its target version is. Alternatively, the mapping of classes to `Cordapp`s obtained by the CorDapp loader could be stored in a global singleton. This singleton would expose a lambda returning the current CorDapp's version information (e.g. `() -> Cordapp.Info`).  
  
Let's assume that we want to change `TimeWindow.Between` to make it inclusive, i.e. change `contains(instant: Instant) = instant >= fromTime && instant < untilTime` to `contains(instant: Instant) = instant >= fromTime && instant <= untilTime`. However, doing so will break existing CorDapps. We could then version-guard the change such that the new behaviour is only used if the target version of the CorDapp calling `contains` is equal to or greater than the platform  version that contains this change. It would look similar to this: 
  
  ```
  fun contains(instant: Instant) {
    if (CorDappVersionResolver.resolve().targetVersion > 42) {
      return instant >= fromTime && instant <= untilTime
    } else {
      return instant >= fromTime && instant < untilTime
    }
  ```
Version-gating API changes when the service hub is available would look similar to the above example, in that case the service hub's CorDapp provider would be used to determine if this code is being called from a CorDapp and to obtain its target version information.
