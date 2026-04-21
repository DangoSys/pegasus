# XDMA Block Design — dual-clock: axi_aclk (250 MHz) + soc_clk (150 MHz).
# XDMA M_AXI (512b) → dwidth_h2c (512→64) → clkconv (250→150) → dma_axi (64b, soc_clk).
# PCIe refclk is differential, buffered by util_ds_buf (IBUFDSGTE).
if {![info exists proj_dir]} {
  error "proj_dir must be set before sourcing create_xdma_bd.tcl"
}

set bd_name pegasus_xdma_bd
create_bd_design $bd_name

# ── XDMA IP ──────────────────────────────────────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.1 xdma_0
set_property -dict [list \
  CONFIG.PCIE_BOARD_INTERFACE        {pci_express_x16} \
  CONFIG.SYS_RST_N_BOARD_INTERFACE   {pcie_perstn} \
  CONFIG.axilite_master_en           {true} \
  CONFIG.axilite_master_size         {32} \
  CONFIG.pf0_msix_cap_pba_bir        {BAR_1} \
  CONFIG.pf0_msix_cap_table_bir      {BAR_1} \
  CONFIG.xdma_axi_intf_mm            {AXI_Memory_Mapped} \
  CONFIG.xdma_rnum_chnl              {4} \
  CONFIG.xdma_wnum_chnl              {4} \
] [get_bd_cells xdma_0]

# ── Dwidth: 512→64, synchronous in axi_aclk ─────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 dwidth_h2c
set_property -dict [list \
  CONFIG.ADDR_WIDTH    {64} \
  CONFIG.SI_DATA_WIDTH {512} \
  CONFIG.MI_DATA_WIDTH {64} \
  CONFIG.SI_ID_WIDTH   {4} \
  CONFIG.ACLK_ASYNC    {0} \
] [get_bd_cells dwidth_h2c]

# ── Clock converter: axi_aclk (250) → soc_clk (150) for DMA AXI output ─────
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 clkconv_dma
set_property -dict [list \
  CONFIG.ADDR_WIDTH  {64} \
  CONFIG.DATA_WIDTH  {64} \
  CONFIG.ID_WIDTH    {4} \
  CONFIG.ACLK_ASYNC  {1} \
] [get_bd_cells clkconv_dma]

# ── SoC clock generator: 250 MHz → 150 MHz ──────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0
set_property -dict [list \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {150.000} \
  CONFIG.USE_LOCKED {true} \
] [get_bd_cells clk_wiz_0]

# ── SoC reset synchronizer ──────────────────────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 rst_soc

# ── PCIe refclk buffer (differential → single-ended for XDMA) ───────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf_0
set_property -dict [list \
  CONFIG.C_BUF_TYPE {IBUFDSGTE} \
  CONFIG.DIFF_CLK_IN_BOARD_INTERFACE {pcie_refclk} \
  CONFIG.USE_BOARD_FLOW {true} \
] [get_bd_cells util_ds_buf_0]

# ── External ports ───────────────────────────────────────────────────────────
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_refclk
set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_ports pcie_refclk]
create_bd_port -dir I -type rst pcie_sys_rst_n
create_bd_port -dir O -type clk axi_aclk
create_bd_port -dir O -type rst axi_aresetn
create_bd_port -dir O -type clk soc_clk
create_bd_port -dir O -type rst soc_aresetn
set_property CONFIG.POLARITY ACTIVE_LOW [get_bd_ports pcie_sys_rst_n]
set_property CONFIG.POLARITY ACTIVE_LOW [get_bd_ports axi_aresetn]
set_property CONFIG.POLARITY ACTIVE_LOW [get_bd_ports soc_aresetn]

# ── IRQ tie-off ──────────────────────────────────────────────────────────────
create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 irq_const
set_property -dict [list CONFIG.CONST_WIDTH {1} CONFIG.CONST_VAL {0}] [get_bd_cells irq_const]
connect_bd_net [get_bd_pins irq_const/dout] [get_bd_pins xdma_0/usr_irq_req]

# ── PCIe refclk wiring ──────────────────────────────────────────────────────
connect_bd_intf_net [get_bd_intf_ports pcie_refclk] [get_bd_intf_pins util_ds_buf_0/CLK_IN_D]
connect_bd_net [get_bd_pins util_ds_buf_0/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]
connect_bd_net [get_bd_pins util_ds_buf_0/IBUF_OUT]      [get_bd_pins xdma_0/sys_clk_gt]
connect_bd_net [get_bd_ports pcie_sys_rst_n] [get_bd_pins xdma_0/sys_rst_n]

# ── axi_aclk (250 MHz from XDMA) ────────────────────────────────────────────
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_ports axi_aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_ports axi_aresetn]

# dwidth_h2c runs on axi_aclk
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins dwidth_h2c/s_axi_aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins dwidth_h2c/s_axi_aresetn]

# ── soc_clk (150 MHz from clk_wiz) ──────────────────────────────────────────
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins clk_wiz_0/clk_in1]
connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_ports soc_clk]
connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins rst_soc/slowest_sync_clk]
connect_bd_net [get_bd_ports pcie_sys_rst_n]     [get_bd_pins rst_soc/ext_reset_in]
connect_bd_net [get_bd_pins clk_wiz_0/locked]    [get_bd_pins rst_soc/dcm_locked]
connect_bd_net [get_bd_pins rst_soc/interconnect_aresetn] [get_bd_ports soc_aresetn]

# ── Clock converter: axi_aclk → soc_clk ─────────────────────────────────────
connect_bd_net [get_bd_pins xdma_0/axi_aclk]    [get_bd_pins clkconv_dma/s_axi_aclk]
connect_bd_net [get_bd_pins xdma_0/axi_aresetn]  [get_bd_pins clkconv_dma/s_axi_aresetn]
connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins clkconv_dma/m_axi_aclk]
connect_bd_net [get_bd_pins rst_soc/interconnect_aresetn] [get_bd_pins clkconv_dma/m_axi_aresetn]

# ── Data path: XDMA M_AXI (512b) → dwidth → clkconv → dma_axi (64b, soc_clk)
connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI]      [get_bd_intf_pins dwidth_h2c/S_AXI]
connect_bd_intf_net [get_bd_intf_pins dwidth_h2c/M_AXI]   [get_bd_intf_pins clkconv_dma/S_AXI]

make_bd_intf_pins_external [get_bd_intf_pins clkconv_dma/M_AXI]
set_property name dma_axi [get_bd_intf_ports M_AXI_0]

# ── AXI-Lite export (stays on axi_aclk — SCU handles CDC internally) ────────
make_bd_intf_pins_external [get_bd_intf_pins xdma_0/M_AXI_LITE]
set_property name dma_axil [get_bd_intf_ports M_AXI_LITE_0]

# ── PCIe MGT export ─────────────────────────────────────────────────────────
make_bd_intf_pins_external [get_bd_intf_pins xdma_0/pcie_mgt]
set_property name pcie_mgt [get_bd_intf_ports pcie_mgt_0]

# ── Address mapping for external interfaces ─────────────────────────────────
assign_bd_address -target_address_space /xdma_0/M_AXI \
  [get_bd_addr_segs dma_axi/Reg] -force
assign_bd_address -target_address_space /xdma_0/M_AXI_LITE \
  [get_bd_addr_segs dma_axil/Reg] -force

# ── Clock frequency annotations ─────────────────────────────────────────────
set_property CONFIG.FREQ_HZ 250000000 [get_bd_ports axi_aclk]
set_property CONFIG.ASSOCIATED_RESET {axi_aresetn} [get_bd_ports axi_aclk]
set_property CONFIG.ASSOCIATED_BUSIF {dma_axil} [get_bd_ports axi_aclk]
set_property CONFIG.FREQ_HZ 150000000 [get_bd_ports soc_clk]
set_property CONFIG.ASSOCIATED_RESET {soc_aresetn} [get_bd_ports soc_clk]
set_property CONFIG.ASSOCIATED_BUSIF {dma_axi} [get_bd_ports soc_clk]

validate_bd_design
save_bd_design

set bd_file [get_files "${proj_dir}/pegasus_build.srcs/sources_1/bd/${bd_name}/${bd_name}.bd"]
set_property synth_checkpoint_mode None $bd_file
generate_target all $bd_file
export_ip_user_files -of_objects $bd_file -no_script -sync -force -quiet
make_wrapper -files $bd_file -top -import

puts "INFO: BD generated: ${bd_name} (wrapper imported)"
