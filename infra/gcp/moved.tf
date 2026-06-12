moved {
  from = google_compute_instance.vm["data-monitor"]
  to   = google_compute_instance.vm["data"]
}

moved {
  from = google_compute_instance.vm["monitor"]
  to   = google_compute_instance.vm["monitoring"]
}

moved {
  from = google_compute_address.internal["data-monitor"]
  to   = google_compute_address.internal["data"]
}

moved {
  from = google_compute_address.internal["monitor"]
  to   = google_compute_address.internal["monitoring"]
}

moved {
  from = google_compute_address.external["data-monitor"]
  to   = google_compute_address.external["data"]
}

moved {
  from = google_compute_address.external["monitor"]
  to   = google_compute_address.external["monitoring"]
}
