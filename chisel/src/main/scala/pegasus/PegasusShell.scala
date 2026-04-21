package pegasus

import chisel3._
import chisel3.util.HasBlackBoxInline

// PegasusShell — AU280 shell with direct DMA-to-DDR architecture.
//
// All logic runs on axi_aclk (~250 MHz) from XDMA. No separate soc_clk.
//
// Data path:
//   XDMA M_AXI (512b) -> BD dwidth (512->64) -> dma_axi (64b, axi_aclk)
//   dma_axi + chip_mem -> axi_ic_ddr4 crossbar (64b) -> dwidth_soc (64->512, async) -> DDR4 MIG
//
// Control path:
//   XDMA M_AXI_LITE (32b, axi_aclk) -> SCU (cpu_hold_reset)
//   DigitalTop mmio_axi4 (64b, axi_aclk) -> SCU (shared registers)
//
// Address mapping:
//   DDR4 AXI addr 0x0 == SoC paddr 0x80000000.
class PegasusShell extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    // PCIe
    val pcie_refclk_p   = Input(Bool())
    val pcie_refclk_n   = Input(Bool())
    val pcie_sys_rst_n  = Input(Bool())
    val pcie_exp_txp   = Output(UInt(16.W))
    val pcie_exp_txn   = Output(UInt(16.W))
    val pcie_exp_rxp   = Input(UInt(16.W))
    val pcie_exp_rxn   = Input(UInt(16.W))

    // DDR4 physical pins
    val c0_sys_clk_p    = Input(Bool())
    val c0_sys_clk_n    = Input(Bool())
    val c0_ddr4_act_n   = Output(Bool())
    val c0_ddr4_adr     = Output(UInt(17.W))
    val c0_ddr4_ba      = Output(UInt(2.W))
    val c0_ddr4_bg      = Output(UInt(2.W))
    val c0_ddr4_cke     = Output(UInt(1.W))
    val c0_ddr4_odt     = Output(UInt(1.W))
    val c0_ddr4_cs_n    = Output(UInt(1.W))
    val c0_ddr4_ck_t    = Output(UInt(1.W))
    val c0_ddr4_ck_c    = Output(UInt(1.W))
    val c0_ddr4_reset_n = Output(Bool())
    val c0_ddr4_parity  = Output(Bool())

    // SoC clock/reset
    val dut_clk   = Output(Clock())
    val dut_reset = Output(Bool())
    val cpu_hold_reset = Output(Bool())
    val uart_tx   = Input(Bool())

    // SoC mem_axi4 (64-bit, 32-bit addr, 4-bit id) → crossbar → DDR4
    val chip_mem_awid    = Input(UInt(4.W))
    val chip_mem_awaddr  = Input(UInt(32.W))
    val chip_mem_awlen   = Input(UInt(8.W))
    val chip_mem_awsize  = Input(UInt(3.W))
    val chip_mem_awburst = Input(UInt(2.W))
    val chip_mem_awvalid = Input(Bool())
    val chip_mem_awready = Output(Bool())
    val chip_mem_wdata  = Input(UInt(64.W))
    val chip_mem_wstrb  = Input(UInt(8.W))
    val chip_mem_wlast  = Input(Bool())
    val chip_mem_wvalid = Input(Bool())
    val chip_mem_wready = Output(Bool())
    val chip_mem_bid    = Output(UInt(4.W))
    val chip_mem_bresp  = Output(UInt(2.W))
    val chip_mem_bvalid = Output(Bool())
    val chip_mem_bready = Input(Bool())
    val chip_mem_arid    = Input(UInt(4.W))
    val chip_mem_araddr  = Input(UInt(32.W))
    val chip_mem_arlen   = Input(UInt(8.W))
    val chip_mem_arsize  = Input(UInt(3.W))
    val chip_mem_arburst = Input(UInt(2.W))
    val chip_mem_arvalid = Input(Bool())
    val chip_mem_arready = Output(Bool())
    val chip_mem_rid    = Output(UInt(4.W))
    val chip_mem_rdata  = Output(UInt(64.W))
    val chip_mem_rresp  = Output(UInt(2.W))
    val chip_mem_rlast  = Output(Bool())
    val chip_mem_rvalid = Output(Bool())
    val chip_mem_rready = Input(Bool())

    // MMIO AXI4 from DigitalTop → SCU
    val mmio_awid    = Input(UInt(4.W))
    val mmio_awaddr  = Input(UInt(32.W))
    val mmio_awlen   = Input(UInt(8.W))
    val mmio_awsize  = Input(UInt(3.W))
    val mmio_awburst = Input(UInt(2.W))
    val mmio_awvalid = Input(Bool())
    val mmio_awready = Output(Bool())
    val mmio_wdata  = Input(UInt(64.W))
    val mmio_wstrb  = Input(UInt(8.W))
    val mmio_wlast  = Input(Bool())
    val mmio_wvalid = Input(Bool())
    val mmio_wready = Output(Bool())
    val mmio_bid    = Output(UInt(4.W))
    val mmio_bresp  = Output(UInt(2.W))
    val mmio_bvalid = Output(Bool())
    val mmio_bready = Input(Bool())
    val mmio_arid    = Input(UInt(4.W))
    val mmio_araddr  = Input(UInt(32.W))
    val mmio_arlen   = Input(UInt(8.W))
    val mmio_arsize  = Input(UInt(3.W))
    val mmio_arburst = Input(UInt(2.W))
    val mmio_arvalid = Input(Bool())
    val mmio_arready = Output(Bool())
    val mmio_rid    = Output(UInt(4.W))
    val mmio_rdata  = Output(UInt(64.W))
    val mmio_rresp  = Output(UInt(2.W))
    val mmio_rlast  = Output(Bool())
    val mmio_rvalid = Output(Bool())
    val mmio_rready = Input(Bool())
  })

  setInline("PegasusShell.v",
    """module PegasusShell (
      |  input         pcie_refclk_p,
      |  input         pcie_refclk_n,
      |  input         pcie_sys_rst_n,
      |  output [15:0] pcie_exp_txp,
      |  output [15:0] pcie_exp_txn,
      |  input  [15:0] pcie_exp_rxp,
      |  input  [15:0] pcie_exp_rxn,
      |  input         c0_sys_clk_p,
      |  input         c0_sys_clk_n,
      |  output        c0_ddr4_act_n,
      |  output [16:0] c0_ddr4_adr,
      |  output [1:0]  c0_ddr4_ba,
      |  output [1:0]  c0_ddr4_bg,
      |  output [0:0]  c0_ddr4_cke,
      |  output [0:0]  c0_ddr4_odt,
      |  output [0:0]  c0_ddr4_cs_n,
      |  output [0:0]  c0_ddr4_ck_t,
      |  output [0:0]  c0_ddr4_ck_c,
      |  output        c0_ddr4_reset_n,
      |  output        c0_ddr4_parity,
      |  inout  [71:0] c0_ddr4_dq,
      |  inout  [17:0] c0_ddr4_dqs_c,
      |  inout  [17:0] c0_ddr4_dqs_t,
      |  output        dut_clk,
      |  output        dut_reset,
      |  output        cpu_hold_reset,
      |  input         uart_tx,
      |  // SoC mem_axi4 → crossbar → DDR4
      |  input  [3:0]   chip_mem_awid,    input  [31:0] chip_mem_awaddr,
      |  input  [7:0]   chip_mem_awlen,   input  [2:0]  chip_mem_awsize,
      |  input  [1:0]   chip_mem_awburst, input         chip_mem_awvalid,
      |  output         chip_mem_awready,
      |  input  [63:0]  chip_mem_wdata,   input  [7:0]  chip_mem_wstrb,
      |  input          chip_mem_wlast,   input         chip_mem_wvalid,
      |  output         chip_mem_wready,
      |  output [3:0]   chip_mem_bid,     output [1:0]  chip_mem_bresp,
      |  output         chip_mem_bvalid,  input         chip_mem_bready,
      |  input  [3:0]   chip_mem_arid,    input  [31:0] chip_mem_araddr,
      |  input  [7:0]   chip_mem_arlen,   input  [2:0]  chip_mem_arsize,
      |  input  [1:0]   chip_mem_arburst, input         chip_mem_arvalid,
      |  output         chip_mem_arready,
      |  output [3:0]   chip_mem_rid,     output [63:0] chip_mem_rdata,
      |  output [1:0]   chip_mem_rresp,   output        chip_mem_rlast,
      |  output         chip_mem_rvalid,  input         chip_mem_rready,
      |  // MMIO AXI4 → SCU
      |  input  [3:0]   mmio_awid,    input  [31:0] mmio_awaddr,
      |  input  [7:0]   mmio_awlen,   input  [2:0]  mmio_awsize,
      |  input  [1:0]   mmio_awburst, input         mmio_awvalid,
      |  output         mmio_awready,
      |  input  [63:0]  mmio_wdata,   input  [7:0]  mmio_wstrb,
      |  input          mmio_wlast,   input         mmio_wvalid,
      |  output         mmio_wready,
      |  output [3:0]   mmio_bid,     output [1:0]  mmio_bresp,
      |  output         mmio_bvalid,  input         mmio_bready,
      |  input  [3:0]   mmio_arid,    input  [31:0] mmio_araddr,
      |  input  [7:0]   mmio_arlen,   input  [2:0]  mmio_arsize,
      |  input  [1:0]   mmio_arburst, input         mmio_arvalid,
      |  output         mmio_arready,
      |  output [3:0]   mmio_rid,     output [63:0] mmio_rdata,
      |  output [1:0]   mmio_rresp,   output        mmio_rlast,
      |  output         mmio_rvalid,  input         mmio_rready
      |);
      |
      |  // ── Clocks & resets ───────────────────────────────────────────────
      |  wire axi_aclk;
      |  wire axi_aresetn;
      |  wire ui_clk;
      |  wire ui_clk_sync_rst;
      |  wire ui_aresetn = ~ui_clk_sync_rst;
      |  wire resetn     = pcie_sys_rst_n;
      |  wire ddr4_sys_rst = ~resetn;
      |
      |  // DUT runs on axi_aclk (unified clock domain)
      |  assign dut_clk   = axi_aclk;
      |  assign dut_reset = ~axi_aresetn;
      |
      |  // ── XDMA BD wrapper wires ─────────────────────────────────────────
      |  // dma_axi: 64-bit AXI from XDMA (after 512→64 dwidth in BD), axi_aclk
      |  // Note: dwidth_h2c strips ID signals; BD wrapper has no awid/bid/arid/rid.
      |  wire [63:0] dma_awaddr;  wire [7:0]  dma_awlen;
      |  wire [2:0]  dma_awsize;  wire [1:0]  dma_awburst;
      |  wire        dma_awlock;  wire [3:0]  dma_awcache;
      |  wire [2:0]  dma_awprot;  wire [3:0]  dma_awqos;  wire [3:0] dma_awregion;
      |  wire        dma_awvalid, dma_awready;
      |  wire [63:0] dma_wdata;   wire [7:0]  dma_wstrb;
      |  wire        dma_wlast,   dma_wvalid, dma_wready;
      |  wire [1:0]  dma_bresp;   wire        dma_bvalid, dma_bready;
      |  wire [63:0] dma_araddr;  wire [7:0]  dma_arlen;
      |  wire [2:0]  dma_arsize;  wire [1:0]  dma_arburst;
      |  wire        dma_arlock;  wire [3:0]  dma_arcache;
      |  wire [2:0]  dma_arprot;  wire [3:0]  dma_arqos;  wire [3:0] dma_arregion;
      |  wire        dma_arvalid, dma_arready;
      |  wire [63:0] dma_rdata;   wire [1:0]  dma_rresp;
      |  wire        dma_rlast,   dma_rvalid, dma_rready;
      |
      |  // AXI-Lite from XDMA → SCU
      |  wire [31:0] axil_awaddr; wire [2:0] axil_awprot;
      |  wire        axil_awvalid, axil_awready;
      |  wire [31:0] axil_wdata;  wire [3:0] axil_wstrb;
      |  wire        axil_wvalid, axil_wready;
      |  wire        axil_bvalid; wire [1:0] axil_bresp; wire axil_bready;
      |  wire [31:0] axil_araddr; wire [2:0] axil_arprot;
      |  wire        axil_arvalid, axil_arready;
      |  wire [31:0] axil_rdata;  wire [1:0] axil_rresp;
      |  wire        axil_rvalid, axil_rready;
      |
      |  // ── XDMA BD instantiation ─────────────────────────────────────────
      |  pegasus_xdma_bd_wrapper xdma_subsys (
      |    .pcie_refclk_clk_p (pcie_refclk_p),
      |    .pcie_refclk_clk_n (pcie_refclk_n),
      |    .pcie_sys_rst_n   (pcie_sys_rst_n),
      |    .axi_aclk         (axi_aclk),
      |    .axi_aresetn      (axi_aresetn),
      |    .pcie_mgt_txn     (pcie_exp_txn),
      |    .pcie_mgt_txp     (pcie_exp_txp),
      |    .pcie_mgt_rxn     (pcie_exp_rxn),
      |    .pcie_mgt_rxp     (pcie_exp_rxp),
      |    // dma_axi: 64-bit AXI (no ID signals from dwidth_h2c)
      |    .dma_axi_awaddr   (dma_awaddr),
      |    .dma_axi_awlen    (dma_awlen),
      |    .dma_axi_awsize   (dma_awsize),
      |    .dma_axi_awburst  (dma_awburst),
      |    .dma_axi_awlock   (dma_awlock),
      |    .dma_axi_awcache  (dma_awcache),
      |    .dma_axi_awprot   (dma_awprot),
      |    .dma_axi_awqos    (dma_awqos),
      |    .dma_axi_awregion (dma_awregion),
      |    .dma_axi_awvalid  (dma_awvalid),
      |    .dma_axi_awready  (dma_awready),
      |    .dma_axi_wdata    (dma_wdata),
      |    .dma_axi_wstrb    (dma_wstrb),
      |    .dma_axi_wlast    (dma_wlast),
      |    .dma_axi_wvalid   (dma_wvalid),
      |    .dma_axi_wready   (dma_wready),
      |    .dma_axi_bresp    (dma_bresp),
      |    .dma_axi_bvalid   (dma_bvalid),
      |    .dma_axi_bready   (dma_bready),
      |    .dma_axi_araddr   (dma_araddr),
      |    .dma_axi_arlen    (dma_arlen),
      |    .dma_axi_arsize   (dma_arsize),
      |    .dma_axi_arburst  (dma_arburst),
      |    .dma_axi_arlock   (dma_arlock),
      |    .dma_axi_arcache  (dma_arcache),
      |    .dma_axi_arprot   (dma_arprot),
      |    .dma_axi_arqos    (dma_arqos),
      |    .dma_axi_arregion (dma_arregion),
      |    .dma_axi_arvalid  (dma_arvalid),
      |    .dma_axi_arready  (dma_arready),
      |    .dma_axi_rdata    (dma_rdata),
      |    .dma_axi_rresp    (dma_rresp),
      |    .dma_axi_rlast    (dma_rlast),
      |    .dma_axi_rvalid   (dma_rvalid),
      |    .dma_axi_rready   (dma_rready),
      |    .dma_axil_awaddr  (axil_awaddr),
      |    .dma_axil_awprot  (axil_awprot),
      |    .dma_axil_awvalid (axil_awvalid),
      |    .dma_axil_awready (axil_awready),
      |    .dma_axil_wdata   (axil_wdata),
      |    .dma_axil_wstrb   (axil_wstrb),
      |    .dma_axil_wvalid  (axil_wvalid),
      |    .dma_axil_wready  (axil_wready),
      |    .dma_axil_bresp   (axil_bresp),
      |    .dma_axil_bvalid  (axil_bvalid),
      |    .dma_axil_bready  (axil_bready),
      |    .dma_axil_araddr  (axil_araddr),
      |    .dma_axil_arprot  (axil_arprot),
      |    .dma_axil_arvalid (axil_arvalid),
      |    .dma_axil_arready (axil_arready),
      |    .dma_axil_rdata   (axil_rdata),
      |    .dma_axil_rresp   (axil_rresp),
      |    .dma_axil_rvalid  (axil_rvalid),
      |    .dma_axil_rready  (axil_rready)
      |  );
      |
      |  // ── SCU (AXI-Lite slave from XDMA, axi_aclk domain) ──────────────
      |  reg [31:0] scu_ctrl;
      |  reg        scu_aw_pend, scu_bvalid_r, scu_rvalid_r;
      |  reg [31:0] scu_rdata_r;
      |
      |  always @(posedge axi_aclk or negedge axi_aresetn) begin
      |    if (!axi_aresetn) begin
      |      scu_ctrl     <= 32'h1;
      |      scu_aw_pend  <= 1'b0;
      |      scu_bvalid_r <= 1'b0;
      |      scu_rvalid_r <= 1'b0;
      |    end else begin
      |      if (axil_awvalid && axil_awready) scu_aw_pend <= 1'b1;
      |      if ((scu_aw_pend || (axil_awvalid && axil_awready)) && axil_wvalid) begin
      |        scu_ctrl     <= axil_wdata;
      |        scu_aw_pend  <= 1'b0;
      |        scu_bvalid_r <= 1'b1;
      |      end
      |      if (scu_bvalid_r && axil_bready) scu_bvalid_r <= 1'b0;
      |      if (axil_arvalid && axil_arready) begin
      |        scu_rdata_r  <= scu_ctrl;
      |        scu_rvalid_r <= 1'b1;
      |      end
      |      if (scu_rvalid_r && axil_rready) scu_rvalid_r <= 1'b0;
      |    end
      |  end
      |  assign axil_awready  = ~scu_aw_pend & ~scu_bvalid_r;
      |  assign axil_wready   = 1'b1;
      |  assign axil_bvalid   = scu_bvalid_r;
      |  assign axil_bresp    = 2'b00;
      |  assign axil_arready  = ~scu_rvalid_r;
      |  assign axil_rdata    = scu_rdata_r;
      |  assign axil_rresp    = 2'b00;
      |  assign axil_rvalid   = scu_rvalid_r;
      |  assign cpu_hold_reset = scu_ctrl[0];
      |
      |  // ── MMIO peripheral (AXI4 slave from DigitalTop, axi_aclk domain) ─
      |  // Serves DigitalTop MMIO requests (UART, sim_exit, etc.).
      |  // Address map (relative to MMIO base):
      |  //   0x00: UART TX data (write: send byte, read: 0)
      |  //   0x08: sim_exit     (write: trigger exit, read: 0)
      |  // For now: accept all writes (discard), reads return 0.
      |  reg        mmio_aw_pend;
      |  reg [3:0]  mmio_bid_r, mmio_rid_r;
      |  reg        mmio_bvalid_r, mmio_rvalid_r;
      |
      |  always @(posedge axi_aclk or negedge axi_aresetn) begin
      |    if (!axi_aresetn) begin
      |      mmio_aw_pend  <= 1'b0;
      |      mmio_bvalid_r <= 1'b0;
      |      mmio_rvalid_r <= 1'b0;
      |    end else begin
      |      if (mmio_awvalid && mmio_awready) begin
      |        mmio_aw_pend <= 1'b1;
      |        mmio_bid_r   <= mmio_awid;
      |      end
      |      if ((mmio_aw_pend || (mmio_awvalid && mmio_awready)) && mmio_wvalid && mmio_wlast) begin
      |        mmio_aw_pend  <= 1'b0;
      |        mmio_bvalid_r <= 1'b1;
      |      end
      |      if (mmio_bvalid_r && mmio_bready) mmio_bvalid_r <= 1'b0;
      |      if (mmio_arvalid && mmio_arready) begin
      |        mmio_rid_r    <= mmio_arid;
      |        mmio_rvalid_r <= 1'b1;
      |      end
      |      if (mmio_rvalid_r && mmio_rready) mmio_rvalid_r <= 1'b0;
      |    end
      |  end
      |  assign mmio_awready = ~mmio_aw_pend & ~mmio_bvalid_r;
      |  assign mmio_wready  = 1'b1;
      |  assign mmio_bid     = mmio_bid_r;
      |  assign mmio_bresp   = 2'b00;
      |  assign mmio_bvalid  = mmio_bvalid_r;
      |  assign mmio_arready = ~mmio_rvalid_r;
      |  assign mmio_rid     = mmio_rid_r;
      |  assign mmio_rdata   = 64'h0;
      |  assign mmio_rresp   = 2'b00;
      |  assign mmio_rlast   = 1'b1;
      |  assign mmio_rvalid  = mmio_rvalid_r;
      |
      |  // ── AXI Crossbar: 2 slaves → 1 master (DDR4 path) ────────────────
      |  // S0: XDMA DMA (64b, axi_aclk)  — no ID from BD dwidth, tie to 0
      |  // S1: DigitalTop mem_axi4 (64b, axi_aclk)
      |  // M0: → dwidth_soc (64→512) → DDR4 MIG
      |  // axi_crossbar standalone IP uses concatenated vector ports:
      |  //   s_axi_awaddr = {s1_addr, s0_addr}, etc.
      |  wire [3:0]  xbar_m_awid;    wire [31:0] xbar_m_awaddr;
      |  wire [7:0]  xbar_m_awlen;   wire [2:0]  xbar_m_awsize;
      |  wire [1:0]  xbar_m_awburst; wire        xbar_m_awlock;
      |  wire [3:0]  xbar_m_awcache; wire [2:0]  xbar_m_awprot;
      |  wire [3:0]  xbar_m_awregion; wire [3:0] xbar_m_awqos;
      |  wire        xbar_m_awvalid, xbar_m_awready;
      |  wire [63:0] xbar_m_wdata;   wire [7:0]  xbar_m_wstrb;
      |  wire        xbar_m_wlast,   xbar_m_wvalid, xbar_m_wready;
      |  wire [3:0]  xbar_m_bid;     wire [1:0]  xbar_m_bresp;
      |  wire        xbar_m_bvalid,  xbar_m_bready;
      |  wire [3:0]  xbar_m_arid;    wire [31:0] xbar_m_araddr;
      |  wire [7:0]  xbar_m_arlen;   wire [2:0]  xbar_m_arsize;
      |  wire [1:0]  xbar_m_arburst; wire        xbar_m_arlock;
      |  wire [3:0]  xbar_m_arcache; wire [2:0]  xbar_m_arprot;
      |  wire [3:0]  xbar_m_arregion; wire [3:0] xbar_m_arqos;
      |  wire        xbar_m_arvalid, xbar_m_arready;
      |  wire [63:0] xbar_m_rdata;   wire [1:0]  xbar_m_rresp;
      |  wire        xbar_m_rlast,   xbar_m_rvalid, xbar_m_rready;
      |  wire [3:0]  xbar_m_rid;
      |
      |  // Crossbar slave-side response wires (concatenated, split per-slave)
      |  wire [7:0]  xbar_s_bid;     wire [3:0]  xbar_s_bresp;
      |  wire [1:0]  xbar_s_bvalid;  wire [1:0]  xbar_s_awready;
      |  wire [1:0]  xbar_s_wready;
      |  wire [7:0]  xbar_s_rid;     wire [127:0] xbar_s_rdata;
      |  wire [3:0]  xbar_s_rresp;   wire [1:0]  xbar_s_rlast;
      |  wire [1:0]  xbar_s_rvalid;  wire [1:0]  xbar_s_arready;
      |
      |  axi_ic_ddr4 xbar (
      |    .aclk           (axi_aclk),
      |    .aresetn        (axi_aresetn),
      |    // Slave ports (concatenated: {S1, S0})
      |    .s_axi_awid     ({chip_mem_awid,    4'h0}),
      |    .s_axi_awaddr   ({chip_mem_awaddr,  dma_awaddr[31:0]}),
      |    .s_axi_awlen    ({chip_mem_awlen,   dma_awlen}),
      |    .s_axi_awsize   ({chip_mem_awsize,  dma_awsize}),
      |    .s_axi_awburst  ({chip_mem_awburst, dma_awburst}),
      |    .s_axi_awlock   ({1'b0,             dma_awlock}),
      |    .s_axi_awcache  ({4'h0,             dma_awcache}),
      |    .s_axi_awprot   ({3'h0,             dma_awprot}),
      |    .s_axi_awqos    ({4'h0,             dma_awqos}),
      |    .s_axi_awvalid  ({chip_mem_awvalid, dma_awvalid}),
      |    .s_axi_awready  (xbar_s_awready),
      |    .s_axi_wdata    ({chip_mem_wdata,   dma_wdata}),
      |    .s_axi_wstrb    ({chip_mem_wstrb,   dma_wstrb}),
      |    .s_axi_wlast    ({chip_mem_wlast,   dma_wlast}),
      |    .s_axi_wvalid   ({chip_mem_wvalid,  dma_wvalid}),
      |    .s_axi_wready   (xbar_s_wready),
      |    .s_axi_bid      (xbar_s_bid),
      |    .s_axi_bresp    (xbar_s_bresp),
      |    .s_axi_bvalid   (xbar_s_bvalid),
      |    .s_axi_bready   ({chip_mem_bready,  dma_bready}),
      |    .s_axi_arid     ({chip_mem_arid,    4'h0}),
      |    .s_axi_araddr   ({chip_mem_araddr,  dma_araddr[31:0]}),
      |    .s_axi_arlen    ({chip_mem_arlen,   dma_arlen}),
      |    .s_axi_arsize   ({chip_mem_arsize,  dma_arsize}),
      |    .s_axi_arburst  ({chip_mem_arburst, dma_arburst}),
      |    .s_axi_arlock   ({1'b0,             dma_arlock}),
      |    .s_axi_arcache  ({4'h0,             dma_arcache}),
      |    .s_axi_arprot   ({3'h0,             dma_arprot}),
      |    .s_axi_arqos    ({4'h0,             dma_arqos}),
      |    .s_axi_arvalid  ({chip_mem_arvalid, dma_arvalid}),
      |    .s_axi_arready  (xbar_s_arready),
      |    .s_axi_rid      (xbar_s_rid),
      |    .s_axi_rdata    (xbar_s_rdata),
      |    .s_axi_rresp    (xbar_s_rresp),
      |    .s_axi_rlast    (xbar_s_rlast),
      |    .s_axi_rvalid   (xbar_s_rvalid),
      |    .s_axi_rready   ({chip_mem_rready,  dma_rready}),
      |    // Master port (single)
      |    .m_axi_awid     (xbar_m_awid),
      |    .m_axi_awaddr   (xbar_m_awaddr),
      |    .m_axi_awlen    (xbar_m_awlen),
      |    .m_axi_awsize   (xbar_m_awsize),
      |    .m_axi_awburst  (xbar_m_awburst),
      |    .m_axi_awlock   (xbar_m_awlock),
      |    .m_axi_awcache  (xbar_m_awcache),
      |    .m_axi_awprot   (xbar_m_awprot),
      |    .m_axi_awregion (xbar_m_awregion),
      |    .m_axi_awqos    (xbar_m_awqos),
      |    .m_axi_awvalid  (xbar_m_awvalid),
      |    .m_axi_awready  (xbar_m_awready),
      |    .m_axi_wdata    (xbar_m_wdata),
      |    .m_axi_wstrb    (xbar_m_wstrb),
      |    .m_axi_wlast    (xbar_m_wlast),
      |    .m_axi_wvalid   (xbar_m_wvalid),
      |    .m_axi_wready   (xbar_m_wready),
      |    .m_axi_bid      (xbar_m_bid),
      |    .m_axi_bresp    (xbar_m_bresp),
      |    .m_axi_bvalid   (xbar_m_bvalid),
      |    .m_axi_bready   (xbar_m_bready),
      |    .m_axi_arid     (xbar_m_arid),
      |    .m_axi_araddr   (xbar_m_araddr),
      |    .m_axi_arlen    (xbar_m_arlen),
      |    .m_axi_arsize   (xbar_m_arsize),
      |    .m_axi_arburst  (xbar_m_arburst),
      |    .m_axi_arlock   (xbar_m_arlock),
      |    .m_axi_arcache  (xbar_m_arcache),
      |    .m_axi_arprot   (xbar_m_arprot),
      |    .m_axi_arregion (xbar_m_arregion),
      |    .m_axi_arqos    (xbar_m_arqos),
      |    .m_axi_arvalid  (xbar_m_arvalid),
      |    .m_axi_arready  (xbar_m_arready),
      |    .m_axi_rid      (xbar_m_rid),
      |    .m_axi_rdata    (xbar_m_rdata),
      |    .m_axi_rresp    (xbar_m_rresp),
      |    .m_axi_rlast    (xbar_m_rlast),
      |    .m_axi_rvalid   (xbar_m_rvalid),
      |    .m_axi_rready   (xbar_m_rready)
      |  );
      |
      |  // Split crossbar slave responses back to individual ports
      |  // S0 = DMA, S1 = chip_mem
      |  assign dma_awready       = xbar_s_awready[0];
      |  assign chip_mem_awready  = xbar_s_awready[1];
      |  assign dma_wready        = xbar_s_wready[0];
      |  assign chip_mem_wready   = xbar_s_wready[1];
      |  assign dma_bresp         = xbar_s_bresp[1:0];
      |  assign dma_bvalid        = xbar_s_bvalid[0];
      |  assign chip_mem_bid      = xbar_s_bid[7:4];
      |  assign chip_mem_bresp    = xbar_s_bresp[3:2];
      |  assign chip_mem_bvalid   = xbar_s_bvalid[1];
      |  assign dma_arready       = xbar_s_arready[0];
      |  assign chip_mem_arready  = xbar_s_arready[1];
      |  assign dma_rdata         = xbar_s_rdata[63:0];
      |  assign dma_rresp         = xbar_s_rresp[1:0];
      |  assign dma_rlast         = xbar_s_rlast[0];
      |  assign dma_rvalid        = xbar_s_rvalid[0];
      |  assign chip_mem_rid      = xbar_s_rid[7:4];
      |  assign chip_mem_rdata    = xbar_s_rdata[127:64];
      |  assign chip_mem_rresp    = xbar_s_rresp[3:2];
      |  assign chip_mem_rlast    = xbar_s_rlast[1];
      |  assign chip_mem_rvalid   = xbar_s_rvalid[1];
      |
      |  // ── dwidth_soc: 64→512, axi_aclk → ui_clk (ACLK_ASYNC=1) ────────
      |  wire [33:0]  ddr_awaddr;  wire [7:0] ddr_awlen;  wire [2:0] ddr_awsize;
      |  wire [1:0]   ddr_awburst; wire       ddr_awvalid, ddr_awready;
      |  wire [511:0] ddr_wdata;   wire [63:0] ddr_wstrb;
      |  wire         ddr_wlast,   ddr_wvalid, ddr_wready;
      |  wire [1:0]   ddr_bresp;   wire       ddr_bvalid, ddr_bready;
      |  wire [33:0]  ddr_araddr;  wire [7:0] ddr_arlen;  wire [2:0] ddr_arsize;
      |  wire [1:0]   ddr_arburst; wire       ddr_arvalid, ddr_arready;
      |  wire [511:0] ddr_rdata;   wire [1:0] ddr_rresp;
      |  wire         ddr_rlast,   ddr_rvalid, ddr_rready;
      |
      |  axi_dwidth dwidth_soc (
      |    .s_axi_aclk    (axi_aclk),
      |    .s_axi_aresetn (axi_aresetn),
      |    .s_axi_awid    (xbar_m_awid),
      |    .s_axi_awaddr  ({2'b0, xbar_m_awaddr}),
      |    .s_axi_awlen   (xbar_m_awlen),
      |    .s_axi_awsize  (xbar_m_awsize),
      |    .s_axi_awburst (xbar_m_awburst),
      |    .s_axi_awlock  (xbar_m_awlock),
      |    .s_axi_awcache (xbar_m_awcache),
      |    .s_axi_awprot  (xbar_m_awprot),
      |    .s_axi_awregion(xbar_m_awregion),
      |    .s_axi_awqos   (xbar_m_awqos),
      |    .s_axi_awvalid (xbar_m_awvalid),
      |    .s_axi_awready (xbar_m_awready),
      |    .s_axi_wdata   (xbar_m_wdata),
      |    .s_axi_wstrb   (xbar_m_wstrb),
      |    .s_axi_wlast   (xbar_m_wlast),
      |    .s_axi_wvalid  (xbar_m_wvalid),
      |    .s_axi_wready  (xbar_m_wready),
      |    .s_axi_bid     (xbar_m_bid),
      |    .s_axi_bresp   (xbar_m_bresp),
      |    .s_axi_bvalid  (xbar_m_bvalid),
      |    .s_axi_bready  (xbar_m_bready),
      |    .s_axi_arid    (xbar_m_arid),
      |    .s_axi_araddr  ({2'b0, xbar_m_araddr}),
      |    .s_axi_arlen   (xbar_m_arlen),
      |    .s_axi_arsize  (xbar_m_arsize),
      |    .s_axi_arburst (xbar_m_arburst),
      |    .s_axi_arlock  (xbar_m_arlock),
      |    .s_axi_arcache (xbar_m_arcache),
      |    .s_axi_arprot  (xbar_m_arprot),
      |    .s_axi_arregion(xbar_m_arregion),
      |    .s_axi_arqos   (xbar_m_arqos),
      |    .s_axi_arvalid (xbar_m_arvalid),
      |    .s_axi_arready (xbar_m_arready),
      |    .s_axi_rid     (),
      |    .s_axi_rdata   (xbar_m_rdata),
      |    .s_axi_rresp   (xbar_m_rresp),
      |    .s_axi_rlast   (xbar_m_rlast),
      |    .s_axi_rvalid  (xbar_m_rvalid),
      |    .s_axi_rready  (xbar_m_rready),
      |    .m_axi_aclk    (ui_clk),
      |    .m_axi_aresetn (ui_aresetn),
      |    .m_axi_awaddr  (ddr_awaddr), .m_axi_awlen(ddr_awlen),
      |    .m_axi_awsize  (ddr_awsize), .m_axi_awburst(ddr_awburst),
      |    .m_axi_awlock  (), .m_axi_awcache(), .m_axi_awprot(),
      |    .m_axi_awregion(), .m_axi_awqos(),
      |    .m_axi_awvalid (ddr_awvalid), .m_axi_awready(ddr_awready),
      |    .m_axi_wdata   (ddr_wdata),   .m_axi_wstrb (ddr_wstrb),
      |    .m_axi_wlast   (ddr_wlast),   .m_axi_wvalid(ddr_wvalid),
      |    .m_axi_wready  (ddr_wready),
      |    .m_axi_bresp   (ddr_bresp),   .m_axi_bvalid(ddr_bvalid),
      |    .m_axi_bready  (ddr_bready),
      |    .m_axi_araddr  (ddr_araddr), .m_axi_arlen(ddr_arlen),
      |    .m_axi_arsize  (ddr_arsize), .m_axi_arburst(ddr_arburst),
      |    .m_axi_arlock  (), .m_axi_arcache(), .m_axi_arprot(),
      |    .m_axi_arregion(), .m_axi_arqos(),
      |    .m_axi_arvalid (ddr_arvalid), .m_axi_arready(ddr_arready),
      |    .m_axi_rdata   (ddr_rdata),   .m_axi_rresp (ddr_rresp),
      |    .m_axi_rlast   (ddr_rlast),   .m_axi_rvalid(ddr_rvalid),
      |    .m_axi_rready  (ddr_rready)
      |  );
      |
      |  // ── DDR4 MIG ──────────────────────────────────────────────────────
      |  ddr4_0 ddr4 (
      |    .sys_rst                   (ddr4_sys_rst),
      |    .c0_sys_clk_p              (c0_sys_clk_p),
      |    .c0_sys_clk_n              (c0_sys_clk_n),
      |    .c0_ddr4_act_n             (c0_ddr4_act_n),
      |    .c0_ddr4_adr               (c0_ddr4_adr),
      |    .c0_ddr4_ba                (c0_ddr4_ba),
      |    .c0_ddr4_bg                (c0_ddr4_bg),
      |    .c0_ddr4_cke               (c0_ddr4_cke),
      |    .c0_ddr4_odt               (c0_ddr4_odt),
      |    .c0_ddr4_cs_n              (c0_ddr4_cs_n),
      |    .c0_ddr4_ck_t              (c0_ddr4_ck_t),
      |    .c0_ddr4_ck_c              (c0_ddr4_ck_c),
      |    .c0_ddr4_reset_n           (c0_ddr4_reset_n),
      |    .c0_ddr4_parity            (c0_ddr4_parity),
      |    .c0_ddr4_dq                (c0_ddr4_dq),
      |    .c0_ddr4_dqs_c             (c0_ddr4_dqs_c),
      |    .c0_ddr4_dqs_t             (c0_ddr4_dqs_t),
      |    .c0_ddr4_ui_clk            (ui_clk),
      |    .c0_ddr4_ui_clk_sync_rst   (ui_clk_sync_rst),
      |    .c0_ddr4_aresetn           (ui_aresetn),
      |    .c0_ddr4_s_axi_ctrl_awvalid(1'b0),
      |    .c0_ddr4_s_axi_ctrl_awaddr (32'h0),
      |    .c0_ddr4_s_axi_ctrl_wvalid (1'b0),
      |    .c0_ddr4_s_axi_ctrl_wdata  (32'h0),
      |    .c0_ddr4_s_axi_ctrl_bready (1'b1),
      |    .c0_ddr4_s_axi_ctrl_arvalid(1'b0),
      |    .c0_ddr4_s_axi_ctrl_araddr (32'h0),
      |    .c0_ddr4_s_axi_ctrl_rready (1'b1),
      |    .c0_ddr4_interrupt         (),
      |    .c0_ddr4_s_axi_awid        (4'h0),
      |    .c0_ddr4_s_axi_awaddr      (ddr_awaddr),
      |    .c0_ddr4_s_axi_awlen       (ddr_awlen),
      |    .c0_ddr4_s_axi_awsize      (ddr_awsize),
      |    .c0_ddr4_s_axi_awburst     (ddr_awburst),
      |    .c0_ddr4_s_axi_awlock      (1'b0),
      |    .c0_ddr4_s_axi_awcache     (4'h0),
      |    .c0_ddr4_s_axi_awprot      (3'h0),
      |    .c0_ddr4_s_axi_awqos       (4'h0),
      |    .c0_ddr4_s_axi_awvalid     (ddr_awvalid),
      |    .c0_ddr4_s_axi_awready     (ddr_awready),
      |    .c0_ddr4_s_axi_wdata       (ddr_wdata),
      |    .c0_ddr4_s_axi_wstrb       (ddr_wstrb),
      |    .c0_ddr4_s_axi_wlast       (ddr_wlast),
      |    .c0_ddr4_s_axi_wvalid      (ddr_wvalid),
      |    .c0_ddr4_s_axi_wready      (ddr_wready),
      |    .c0_ddr4_s_axi_bid         (),
      |    .c0_ddr4_s_axi_bresp       (ddr_bresp),
      |    .c0_ddr4_s_axi_bvalid      (ddr_bvalid),
      |    .c0_ddr4_s_axi_bready      (ddr_bready),
      |    .c0_ddr4_s_axi_arid        (4'h0),
      |    .c0_ddr4_s_axi_araddr      (ddr_araddr),
      |    .c0_ddr4_s_axi_arlen       (ddr_arlen),
      |    .c0_ddr4_s_axi_arsize      (ddr_arsize),
      |    .c0_ddr4_s_axi_arburst     (ddr_arburst),
      |    .c0_ddr4_s_axi_arlock      (1'b0),
      |    .c0_ddr4_s_axi_arcache     (4'h0),
      |    .c0_ddr4_s_axi_arprot      (3'h0),
      |    .c0_ddr4_s_axi_arqos       (4'h0),
      |    .c0_ddr4_s_axi_arvalid     (ddr_arvalid),
      |    .c0_ddr4_s_axi_arready     (ddr_arready),
      |    .c0_ddr4_s_axi_rid         (),
      |    .c0_ddr4_s_axi_rdata       (ddr_rdata),
      |    .c0_ddr4_s_axi_rresp       (ddr_rresp),
      |    .c0_ddr4_s_axi_rlast       (ddr_rlast),
      |    .c0_ddr4_s_axi_rvalid      (ddr_rvalid),
      |    .c0_ddr4_s_axi_rready      (ddr_rready)
      |  );
      |
      |endmodule
      |""".stripMargin
  )
}
