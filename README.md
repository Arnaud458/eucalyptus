bonjour
Eucalyptus Cloud Platform
=========================

Eucalyptus is open source software for building Amazon Web Services-compatible private and hybrid clouds.

Requirements and Technical Notes
================================

* Eucalyptus is broken into five components: Cloud Controller, Walrus,
  Cluster Controller, Storage Controller, Node Controller. There is also
  an optional, proprietary VMware broker plugin, available from Eucalyptus
  Systems. These components are software services and are arranged in
  three layers: cloud, cluster, and nodes. You can install the components
  on the same physical server or on separate physical servers as business,
  security, and resource needs dictate.

* The Node Controller requires libvirt and KVM.

* The libraries that Eucalyptus depends on are described in detail in
  the INSTALL file.

* You will have to choose one of several network modes, which are
  described in the installation guide. Your system's and network's
  requirements will vary based on which mode you choose.

* Generally, you will need two IP address ranges. The first range
  is private, to be used only within the Eucalyptus system itself. The
  second range is public, to be routable to and from end-users and VM
  instances. Both sets must be unique to Eucalyptus, not in use by other
  components or applications within your network. Static mode requires
  only one available IP address range, and system mode does not require
  an available IP address range.

License
=======

Eucalyptus is published under a BSD license. For more information, see the LICENSE file.

Building Eucalyptus
===================

For information about building Eucalyptus, see the INSTALL file.

Useful Information for Future Trivia Games
==========================================

The product name "Eucalyptus" was originally an acronym, derived from "Elastic Utility Computing Architecture for Linking Your Programs To Useful Systems."
